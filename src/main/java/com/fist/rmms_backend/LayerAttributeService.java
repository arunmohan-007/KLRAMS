package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The attribute model behind Layer Management: what fields each layer carries,
 * what type they are, which of them place the feature, and which may be left
 * blank.
 *
 * <h2>Attributes are jsonb keys, not columns</h2>
 * Every layer table in KLRAMS already carries an {@code attrs jsonb} bag —
 * {@code road_assets} has stored its per-asset fields that way from the start
 * ("Section Label", "CBR", "Soil Type"), and user layers get the same column.
 * So an attribute here is a KEY inside that bag, never a physical column. Three
 * things follow, all of them the reason for the choice:
 *
 * <ul>
 *   <li>Adding an attribute needs no DDL, so it cannot fail halfway or lock a
 *       table that the map is reading.</li>
 *   <li>Renaming one is a jsonb key migration ({@code attrs - old || …}), which
 *       is a single UPDATE rather than an {@code ALTER TABLE} that would break
 *       every query naming the old column.</li>
 *   <li>Layers whose fields live in real columns ({@code roads},
 *       {@code condition}, {@code traffic_stations}) are described here rather
 *       than restructured — their attributes are discovered from
 *       {@code information_schema}, the same trick {@link RoadColumns} already
 *       uses so a shapefile re-import that adds a field needs no Java change.</li>
 * </ul>
 *
 * <h2>Placement roles</h2>
 * A linearly-referenced layer cannot be imported unless the registry knows
 * WHICH attribute carries the section label and WHICH carries the chainage.
 * That is what {@code role} records, and why it is validated rather than left
 * to a naming convention: the asset importer currently guesses from a list of
 * aliases ({@code section_label}, {@code section_la}, {@code label}, {@code road}
 * …), which works until someone uploads a file that spells it differently.
 */
@Service
public class LayerAttributeService {

    private static final Logger log = LoggerFactory.getLogger(LayerAttributeService.class);

    /** The only date format KLRAMS accepts; there is deliberately no second option. */
    public static final String DATE_FORMAT = "dd-MMM-yyyy";

    private static final Set<String> TYPES =
            Set.of("STRING", "INTEGER", "DECIMAL", "DATE", "BOOLEAN", "LOOKUP");

    /** Roles that place a feature. Exactly one attribute may hold each. */
    private static final Set<String> ROLES =
            Set.of("NONE", "SECTION_LABEL", "CHAINAGE", "START_CHAINAGE", "END_CHAINAGE");

    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;

    public LayerAttributeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deliberately NOT {@code @PostConstruct}.
     *
     * {@code layer_attribute} has a foreign key to {@code layer_definition} and
     * seeds itself by reading that table, so it cannot be built until the layer
     * registry has created AND seeded its own. Spring constructs this bean first
     * (the registry depends on it, not the other way round), so a
     * {@code @PostConstruct} here would run against a table that does not exist
     * yet. {@link LayerRegistryService#ensure()} calls this once it is done.
     */
    public void ensure() {
        try {
            ensureSchema();
            seedFromExistingData();
        } catch (Exception e) {
            log.error("Attribute registry init failed — the Attribute Data module may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_attribute (
                id            serial PRIMARY KEY,
                layer_id      integer NOT NULL REFERENCES layer_definition(id) ON DELETE CASCADE,
                dataset_key   text NOT NULL DEFAULT 'default',
                name          text NOT NULL,
                storage_key   text NOT NULL,
                data_type     text NOT NULL,
                length        integer,
                unit          text,
                date_format   text,
                lookup_key    text,
                mandatory     boolean NOT NULL DEFAULT false,
                role          text NOT NULL DEFAULT 'NONE',
                attribute_type text NOT NULL DEFAULT 'STANDARD',
                status        text NOT NULL DEFAULT 'ACTIVE',
                sort_order    integer NOT NULL DEFAULT 100,
                created_at    timestamp NOT NULL DEFAULT now()
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS layer_attribute_layer_idx "
                + "ON layer_attribute(layer_id, dataset_key)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS layer_attribute_key_ux "
                + "ON layer_attribute(layer_id, dataset_key, storage_key)");

        /* The header spellings an upload may use for this attribute, comma
           separated. Districts do not agree on them ("Section_Label" in the
           Malappuram returns, "Section Label" in the Idukki ones), so the
           importer matches on this list rather than on the label alone. */
        jdbc.execute("ALTER TABLE layer_attribute ADD COLUMN IF NOT EXISTS aliases text");

        /* The label the catalogue last wrote. Re-seeding corrects a label only
           while it still matches this, which is what lets a shipped correction
           reach an existing database WITHOUT overwriting a name the RMMS cell
           has since chosen. Once they rename an attribute, it is theirs. */
        jdbc.execute("ALTER TABLE layer_attribute ADD COLUMN IF NOT EXISTS seeded_name text");

        /* Marks a "counts" attribute whose values should be SUMMED at import
           rather than skipped as a meta column — the classified vehicle-type
           columns of a wide traffic-count return (Bike - Scooter, Auto
           Rickshaw, …), as opposed to Station Name/Date/Time/Direction. See
           index.html's trfAggregate(): an attribute the importer resolves with
           this flag on is counted under this attribute's canonical name
           (folding any alias spelling onto it); one it resolves without the
           flag is excluded; an unresolved column still falls back to being
           counted under its own raw header, exactly as before this existed. */
        jdbc.execute("ALTER TABLE layer_attribute ADD COLUMN IF NOT EXISTS vehicle_count boolean NOT NULL DEFAULT false");

        /* Lookups get their own tables here rather than in the Lookup module,
           because an attribute may reference a set before that module is built
           and a dangling reference would be worse than an empty set. */
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lookup_set (
                id         serial PRIMARY KEY,
                set_key    text UNIQUE NOT NULL,
                name       text NOT NULL,
                created_at timestamp NOT NULL DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lookup_value (
                id         serial PRIMARY KEY,
                set_id     integer NOT NULL REFERENCES lookup_set(id) ON DELETE CASCADE,
                code       text NOT NULL,
                label      text NOT NULL,
                sort_order integer NOT NULL DEFAULT 100
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS lookup_value_ux ON lookup_value(set_id, code)");

        /* Repair rows seeded before the role/type rule below existed: a chainage
           typed String would be rejected by validateRoleType() the first time
           anyone edited it, leaving an attribute that cannot be saved. Idempotent. */
        jdbc.update("UPDATE layer_attribute SET data_type = 'DECIMAL' "
                + "WHERE role LIKE '%CHAINAGE' AND data_type NOT IN ('DECIMAL','INTEGER')");
        jdbc.update("UPDATE layer_attribute SET data_type = 'STRING' "
                + "WHERE role = 'SECTION_LABEL' AND data_type <> 'STRING'");
    }

    /* ------------------------------------------------------------------
       Seeding
       ------------------------------------------------------------------ */

    /**
     * Describe the attributes every layer carries.
     *
     * Two passes, in this order and for this reason:
     * <ol>
     *   <li>{@link LayerAttributeCatalog} — the declared column list of the RMMS
     *       Format-B returns. Runs first so the canonical label and storage key
     *       win, and so a layer with no data yet is still fully described.</li>
     *   <li>Discovery — the columns and jsonb keys actually present. Picks up
     *       anything the catalogue does not predict, so a district that ships an
     *       extra column still sees it.</li>
     * </ol>
     *
     * Additive by {@code storage_key}: an attribute that already exists is never
     * re-inserted, and its label is corrected only while the RMMS cell has not
     * renamed it (see {@code seeded_name}). That is what makes this safe to
     * re-run on every boot.
     */
    private void seedFromExistingData() {
        jdbc.query("SELECT id, layer_key, source_table, source_type, placement, geometry_type "
                + "FROM layer_definition", rs -> {
            int layerId = rs.getInt("id");
            String key = rs.getString("layer_key");
            String table = rs.getString("source_table");
            String sourceType = rs.getString("source_type");
            String placement = rs.getString("placement");
            String geometry = rs.getString("geometry_type");
            try {
                seedLayer(layerId, key, table, sourceType, placement, geometry);
            } catch (Exception e) {
                // One bad layer must not abort the rest of the seed.
                log.warn("Could not seed attributes for layer {}: {}", key, e.toString());
            }
        });
    }

    private void seedLayer(int layerId, String key, String table,
                           String sourceType, String placement, String geometry) {
        // The declared catalogue first, every boot — this is a top-up, not a
        // one-shot, so a layer described before a column was catalogued gains it
        // on the next restart instead of staying permanently short.
        for (String ds : LayerAttributeCatalog.datasetsOf(key)) {
            seedFromCatalog(layerId, key, ds);
        }

        // Discovery only fills the gaps the catalogue leaves. A layer the
        // catalogue fully describes still runs it, so an unexpected column in a
        // district's return is picked up rather than silently dropped.
        switch (key) {
            case "roads", "full_road_network", "condition", "traffic_stations" ->
                    seedFromColumns(layerId, table, placement, geometry);

            // Traffic carries TWO datasets: the stations themselves, and the
            // counts recorded at each. They are separate uploads with separate
            // shapes, so they get separate attribute sets on one layer.
            case "traffic_stations_counts" -> { /* handled below */ }

            // A boundary's fields are the properties of the features inside its
            // GeoJSON document, not columns of the one row that holds it — so
            // they have to be read out of the document itself.
            case "boundary_district", "boundary_constituency" ->
                    seedFromBoundaryProps(layerId, key.substring("boundary_".length()));

            case "fwd", "bridge", "culvert", "furniture_line", "furniture_point",
                 "subgrade", "bituminous_core", "pavement_crust" -> {
                    // Before discovery, so rows imported under an older spelling
                    // are folded onto the canonical key and discovery does not
                    // then re-create the old spelling as its own attribute.
                    canonicaliseStoredKeys(key);
                    seedFromAssetAttrs(layerId, key, placement, geometry);
            }

            default -> {
                // System-generated layers describe what they expose, not what is
                // uploaded — there is no import target to map onto.
                if ("SYSTEM_GENERATED".equals(sourceType)) seedDerived(layerId, key);
            }
        }
    }

    /**
     * Write the declared column list of one layer dataset.
     *
     * Insert is additive — {@code ON CONFLICT DO NOTHING} on the storage key —
     * so this only ever gains columns. The label is a separate UPDATE guarded on
     * {@code seeded_name}, which is what lets a corrected label ship with a
     * restart while an attribute the RMMS cell has renamed keeps their name.
     */
    private void seedFromCatalog(int layerId, String layerKey, String dataset) {
        int sort = 10;
        for (LayerAttributeCatalog.Attr a : LayerAttributeCatalog.forLayer(layerKey, dataset)) {
            insert(layerId, dataset, a.name(), a.storageKey(), a.dataType(), null,
                    a.unit(), a.role(), a.mandatory(), "STANDARD", sort,
                    aliasCsv(a));
            sort += 10;
        }
        mergeDuplicates(layerId, dataset, layerKey);

        // The classified vehicle-type columns are inserted like any other
        // declared attribute above; this is the one thing that sets them
        // apart — see the vehicle_count column comment in ensureSchema().
        // Gated on "untouched" like every other seeded correction, so a
        // column the RMMS cell has since renamed is left exactly as they set it.
        if ("traffic_stations".equals(layerKey) && "counts".equals(dataset)) {
            for (String key : LayerAttributeCatalog.trafficVehicleClassKeys()) {
                jdbc.update("""
                    UPDATE layer_attribute SET vehicle_count = true
                     WHERE layer_id = ? AND dataset_key = ? AND storage_key = ?
                       AND (seeded_name IS NULL OR seeded_name = name)
                    """, layerId, dataset, key);
            }
        }
    }

    /**
     * Fold pre-catalogue duplicates into the attribute that now owns them.
     *
     * The discovery seed that ran before this catalogue existed created one
     * attribute per spelling it found, so a layer can already hold "Section
     * Label" beside "section_label" and "Start Chiange" beside "start_chainage".
     * Left alone they are not merely untidy: two attributes claiming the same
     * placement role make BOTH of them un-editable, because
     * {@link #assertRoleFree} refuses to save either while the other holds it.
     *
     * A duplicate is deleted, not retired: it describes the same field as the
     * attribute that absorbs it, whose alias list now covers its spelling, so
     * every stored value still resolves — to one name instead of two. Only rows
     * the RMMS cell has not touched go: a renamed one is theirs, and a CUSTOM one
     * was never ours to reconcile.
     */
    private void mergeDuplicates(int layerId, String dataset, String layerKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, storage_key, name, seeded_name FROM layer_attribute "
              + "WHERE layer_id = ? AND dataset_key = ? AND attribute_type = 'STANDARD'",
                layerId, dataset);

        for (LayerAttributeCatalog.Attr a : LayerAttributeCatalog.forLayer(layerKey, dataset)) {
            Set<String> owned = new HashSet<>();
            for (String alias : aliasCsv(a).split(",")) owned.add(norm(alias));

            for (Map<String, Object> r : rows) {
                String key = String.valueOf(r.get("storage_key"));
                // Compared verbatim, NOT normalised: "Section Label" and
                // "section_label" are the exact pair this has to separate, and
                // normalising first makes the duplicate look like the canonical
                // row and skip itself.
                if (key.equals(a.storageKey()) || !owned.contains(norm(key))) continue;
                Object seeded = r.get("seeded_name");
                boolean untouched = seeded == null || seeded.equals(r.get("name"));
                if (!untouched) continue;
                jdbc.update("DELETE FROM layer_attribute WHERE id = ?", r.get("id"));
                log.info("Merged duplicate attribute \"{}\" into \"{}\" on layer {}",
                        key, a.storageKey(), layerKey);
            }
        }
    }

    /**
     * The accepted header spellings for a declared attribute.
     *
     * The label itself leads the list: a file whose header already matches the
     * canonical name is the common case, and the importer resolves this one
     * string rather than special-casing the name outside the alias list.
     */
    private static String aliasCsv(LayerAttributeCatalog.Attr a) {
        List<String> all = new ArrayList<>();
        // Deduped on the NORMALISED form, because that is how the list is
        // matched: "Section Label" and "Section_Label" are one spelling to the
        // importer, so listing both only makes the column harder to read.
        Set<String> seen = new HashSet<>();
        for (String s : concat(a.name(), a.storageKey(), a.aliases())) {
            if (s != null && !s.isBlank() && seen.add(norm(s))) all.add(s.trim());
        }
        return String.join(",", all);
    }

    private static List<String> concat(String first, String second, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(first);
        out.add(second);
        out.addAll(rest);
        return out;
    }

    /**
     * Layers whose fields are real columns: read the rest from the catalogue.
     *
     * Runs after {@link #seedFromCatalog}, so a column the catalogue already
     * named keeps that name and only the unpredicted ones fall back to the
     * mechanical {@link #prettify} guess.
     */
    private void seedFromColumns(int layerId, String table, String placement, String geometry) {
        if (table == null || !SAFE_TABLE.matcher(table).matches()) return;
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length "
              + "FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position", table);
        int sort = 500;
        for (Map<String, Object> c : cols) {
            String col = String.valueOf(c.get("column_name"));
            if (col.equals("id") || col.equals("geom") || col.equals("attrs")) continue;
            Integer len = (c.get("character_maximum_length") instanceof Number n) ? n.intValue() : null;
            String role = roleFor(col, placement, geometry);
            String type = role.endsWith("CHAINAGE") ? "DECIMAL"
                        : "SECTION_LABEL".equals(role) ? "STRING"
                        : sqlTypeToAttrType(String.valueOf(c.get("data_type")));
            // The road network is whatever the last shapefile import created and
            // DBF truncates every field name to 10 characters, so its columns can
            // only be labelled from the catalogue's lookup, never declared.
            String declared = LayerAttributeCatalog.roadLabel(col);
            insert(layerId, "default", declared != null ? declared : prettify(col), col,
                    type, len, LayerAttributeCatalog.roadUnit(col),
                    role, false, "STANDARD", sort, declared);
            sort += 10;
        }
        markPlacementMandatory(layerId, "default");
    }

    /**
     * Rewrite already-stored {@code attrs} keys onto the canonical storage key.
     *
     * The importer now writes every column under the key its layer declares, but
     * rows loaded before that still carry whatever their file's header said. Two
     * districts of the same layer would then answer to two different keys —
     * {@code Section_Label} for Malappuram, {@code Section Label} for Idukki —
     * and a card or filter that finds the value in one would miss it in the
     * other. This closes that gap for the rows already on disk, so the layer is
     * consistent rather than split by import date.
     *
     * Values are untouched: only the key moves. A row that already holds the
     * canonical key is skipped, so a partially-migrated layer cannot have a
     * newer value overwritten by an older alias.
     *
     * Runs on every boot and is cheap after the first: the {@code jsonb_exists}
     * guard means a migrated layer matches no rows at all.
     */
    private void canonicaliseStoredKeys(String assetType) {
        for (LayerAttributeCatalog.Attr a : LayerAttributeCatalog.forLayer(assetType, "default")) {
            String canonical = a.storageKey();
            for (String alias : aliasCsv(a).split(",")) {
                if (alias.isBlank() || alias.equals(canonical)) continue;
                try {
                    int n = jdbc.update("""
                        UPDATE road_assets
                           SET attrs = (attrs - ?) || jsonb_build_object(?, attrs -> ?)
                         WHERE asset_type = ?
                           AND jsonb_exists(attrs, ?)
                           AND NOT jsonb_exists(attrs, ?)
                        """, alias, canonical, alias, assetType, alias, canonical);
                    if (n > 0) {
                        log.info("Canonicalised {} row(s) of {}: attrs key \"{}\" -> \"{}\"",
                                n, assetType, alias, canonical);
                    }
                } catch (Exception e) {
                    // One key that will not move must not stop the others, and
                    // must never stop the app booting.
                    log.warn("Could not canonicalise attrs key \"{}\" on {}: {}",
                            alias, assetType, e.toString());
                }
            }
        }
    }

    /** road_assets-backed layers: pick up any jsonb key the catalogue did not declare. */
    private void seedFromAssetAttrs(int layerId, String assetType, String placement, String geometry) {
        List<String> keys = jdbc.queryForList(
                "SELECT DISTINCT k FROM road_assets, jsonb_object_keys(attrs) AS k "
              + "WHERE asset_type = ? ORDER BY k", String.class, assetType);
        int sort = 500;
        for (String k : keys) {
            String role = roleFor(k, placement, geometry);
            // The role decides the type, not the name. FWD's chainage columns are
            // literally called "From" and "To", which no name-based guess reads as
            // numeric — and a chainage typed String would fail the very validation
            // this service applies when the attribute is later edited.
            String type = role.endsWith("CHAINAGE") ? "DECIMAL"
                        : "SECTION_LABEL".equals(role) ? "STRING"
                        : guessTypeFromName(k);
            // A jsonb key that the catalogue already declares under a different
            // storage key (a district's alternative spelling) would otherwise be
            // inserted a second time as its own attribute.
            if (aliasOwner(layerId, "default", k) != null) continue;
            insert(layerId, "default", k, k, type, null, null, role, false,
                    "STANDARD", sort, k);
            sort += 10;
        }
        // A layer with no rows yet still needs its placement attributes, or it
        // could never be imported into in the first place.
        ensurePlacementAttributes(layerId, "default", placement, geometry);
        markPlacementMandatory(layerId, "default");
    }

    /**
     * Discover a boundary layer's fields from the GeoJSON it stores.
     *
     * The {@code boundary} table is {@code (type text PRIMARY KEY, geojson
     * text)} — one whole FeatureCollection per row — so there are no per-feature
     * columns for {@link #seedFromColumns} to read and no {@code attrs} bag for
     * {@link #seedFromAssetAttrs}. The fields anyone actually wants to style,
     * label or filter by are the properties of the features INSIDE the document,
     * and which ones exist depends entirely on the shapefile that was uploaded:
     * the district boundary carries DISTRICT, the constituency boundary carries
     * ac, ac_name, pc, pc_name and state.
     *
     * The storage key is the RAW property name, upper-case and all — a map
     * expression reads {@code ['get', 'DISTRICT']}, so anything tidier here
     * would name a key no feature holds. Only the label is made readable.
     *
     * Nothing is pruned when the query finds no keys: an empty result means the
     * boundary has not been uploaded yet, which is not evidence that a
     * previously discovered field has gone away.
     */
    private void seedFromBoundaryProps(int layerId, String boundaryType) {
        List<String> keys;
        try {
            keys = jdbc.queryForList("""
                SELECT DISTINCT k
                  FROM boundary b,
                       jsonb_array_elements((b.geojson::jsonb)->'features') f,
                       jsonb_object_keys(f->'properties') k
                 WHERE b.type = ?
                 ORDER BY k
                """, String.class, boundaryType);
        } catch (Exception e) {
            // No boundary table yet, or a document that is not valid JSON.
            // Neither is worth failing the whole attribute seed over.
            log.debug("Could not read boundary properties for {}: {}", boundaryType, e.toString());
            return;
        }
        if (keys.isEmpty()) return;

        int sort = 10;
        for (String k : keys) {
            String declared = LayerAttributeCatalog.boundaryLabel(k);
            insert(layerId, "default", declared != null ? declared : prettify(k), k,
                    guessTypeFromName(k), null, null, "NONE", false, "STANDARD", sort, k);
            sort += 10;
        }

        /* Retire the two attributes this layer used to be seeded with.
           "Boundary Type" (type) and "Name" (name) were declared on the
           assumption that a boundary has exactly those two fields; neither names
           a property any feature carries, so both would colour every feature by
           its fallback and label every one of them blank.

           Retired rather than deleted, and only while nobody has renamed them —
           the same rule every other correction in this file follows. A retired
           attribute keeps whatever it was, stays visible on the Attribute Data
           screen, and can be switched back on if a district ever does upload a
           boundary whose features really do carry a `name`. */
        for (String stale : new String[]{"type", "name"}) {
            if (keys.contains(stale)) continue;
            jdbc.update("""
                UPDATE layer_attribute SET status = 'RETIRED'
                 WHERE layer_id = ? AND dataset_key = 'default' AND storage_key = ?
                   AND status = 'ACTIVE'
                   AND (seeded_name IS NULL OR seeded_name = name)
                """, layerId, stale);
        }
    }

    private void seedDerived(int layerId, String key) {
        if (key.startsWith("pci")) {
            insert(layerId, "default", "Section Label", "section_label", "STRING", 25,
                    null, "SECTION_LABEL", true, "STANDARD", 10);
            insert(layerId, "default", "PCI", "pci", "DECIMAL", null, null, "NONE", false, "STANDARD", 20);
        } else if ("iri_2km".equals(key)) {
            insert(layerId, "default", "Section Label", "section_label", "STRING", 25,
                    null, "SECTION_LABEL", true, "STANDARD", 10);
            insert(layerId, "default", "Worst Lane IRI", "iri", "DECIMAL", null, "m/km",
                    "NONE", false, "STANDARD", 20);
        }
    }

    /* ------------------------------------------------------------------
       Placement attributes
       ------------------------------------------------------------------ */

    /**
     * Guarantee a linearly-referenced layer has the attributes it needs to be
     * placed at all: a section label always, plus a single chainage for a point
     * or a from/to pair for a line.
     *
     * Called both when seeding an existing layer and when the wizard creates a
     * new one, so a user never has to know that these are required — which is
     * the whole point of generating them rather than validating them later.
     */
    public void ensurePlacementAttributes(int layerId, String dataset,
                                          String placement, String geometry) {
        if (!"LINEAR_REFERENCE".equals(placement)) return;

        if (!hasRole(layerId, dataset, "SECTION_LABEL")) {
            insert(layerId, dataset, "Section Label", "section_label", "STRING", 25, null,
                    "SECTION_LABEL", true, "STANDARD", 1);
        }
        if ("POINT".equals(geometry)) {
            if (!hasRole(layerId, dataset, "CHAINAGE")) {
                insert(layerId, dataset, "Chainage", "chainage", "DECIMAL", null, "m",
                        "CHAINAGE", true, "STANDARD", 2);
            }
        } else {
            if (!hasRole(layerId, dataset, "START_CHAINAGE")) {
                insert(layerId, dataset, "From Chainage", "start_chainage", "DECIMAL", null, "m",
                        "START_CHAINAGE", true, "STANDARD", 2);
            }
            if (!hasRole(layerId, dataset, "END_CHAINAGE")) {
                insert(layerId, dataset, "To Chainage", "end_chainage", "DECIMAL", null, "m",
                        "END_CHAINAGE", true, "STANDARD", 3);
            }
        }
    }

    /**
     * The coordinate pair a lat/long layer is placed from.
     *
     * Given no role of their own — a role marks a LINEAR-reference column, and
     * these are not that — but made mandatory, because a lat/long layer with an
     * unmapped coordinate column cannot place a single feature.
     */
    public void ensureLatLngAttributes(int layerId, String dataset) {
        if (!exists(layerId, dataset, "lat")) {
            insert(layerId, dataset, "Latitude", "lat", "DECIMAL", null, "deg",
                    "NONE", true, "STANDARD", 1);
        }
        if (!exists(layerId, dataset, "lng")) {
            insert(layerId, dataset, "Longitude", "lng", "DECIMAL", null, "deg",
                    "NONE", true, "STANDARD", 2);
        }
    }

    /**
     * Adopt a file's own columns as this layer's attributes.
     *
     * <h2>Why a layer would have no attribute list of its own</h2>
     * Every other layer is described BEFORE anything is loaded into it: the
     * catalogue declares the RMMS returns, and Layer Management describes a
     * permanent user layer. A temporary layer has neither. It is created and
     * loaded in one action from whatever file someone happens to be holding, so
     * there is nobody to ask what its columns should be — and a layer with no
     * attributes stores no attributes, which is how a 29-column KML used to
     * import as 15 bare geometries with an empty popup.
     *
     * So the file describes the layer. Each column becomes one attribute named
     * exactly as the header spells it, which is also registered as its alias —
     * that is what makes {@link LayerDataService#preview} match all of them
     * automatically, so a temporary layer needs no mapping step at all.
     *
     * Additive and order-preserving. A column the layer already knows is
     * skipped rather than duplicated — the generated {@code lat}/{@code lng} of
     * a coordinate-placed layer, a second call with the same file, or a header
     * that differs from one already adopted only in case or punctuation. That
     * last one is not tidiness: {@link LayerDataService#preview} matches columns
     * on the same normalised form, so it could only ever fill one of the two
     * and the second attribute would sit empty on every row.
     *
     * @param columns {@code [{name, dataType}]} in file order; a bare string is
     *                accepted and typed String. Unknown types become String
     *                rather than failing the import that follows.
     * @return how many attributes were created
     */
    @Transactional
    public int adoptFileColumns(int layerId, String dataset, List<?> columns) {
        if (columns == null || columns.isEmpty()) return 0;
        String ds = dataset == null ? "default" : dataset;

        /* Every spelling the layer already answers to, read ONCE. The per-header
           lookup this replaces re-read the whole attribute list for each column,
           which on the 300-column file the cap below allows is 300 queries to
           learn something that does not change while the loop runs. */
        Set<String> known = new HashSet<>();
        Set<String> taken = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT storage_key, name, aliases FROM layer_attribute "
              + "WHERE layer_id = ? AND dataset_key = ?", layerId, ds)) {
            known.add(norm(String.valueOf(r.get("storage_key"))));
            known.add(norm(String.valueOf(r.get("name"))));
            Object aliases = r.get("aliases");
            if (aliases == null) continue;
            for (String alias : String.valueOf(aliases).split(",")) known.add(norm(alias));
        }
        known.remove("");   // a header of pure punctuation matches nothing

        int sort = 100, made = 0;
        for (Object o : columns) {
            String header = (o instanceof Map<?, ?> m) ? str(m.get("name"), null) : str(o, null);
            if (header == null) continue;
            header = header.trim();
            if (header.length() > 120) header = header.substring(0, 120);

            // Already described — by the placement attributes generated with the
            // layer, by an earlier call, or by a column adopted a moment ago.
            // Adding it anyway would give one field two names, one of which
            // nothing would ever fill.
            String seen = norm(header);
            if (!seen.isEmpty() && !known.add(seen)) continue;

            String base = slug(header);
            if (base.isEmpty()) base = "field";
            if (base.length() > 50) base = base.substring(0, 50);
            String storage = base;
            // "Road Name" and "road_name" are two columns in the file and must
            // stay two attributes, however identically they slug.
            for (int n = 2; taken.contains(storage) || exists(layerId, ds, storage); n++) {
                storage = base + "_" + n;
            }
            taken.add(storage);

            String type = "STRING";
            if (o instanceof Map<?, ?> m) {
                String asked = str(m.get("dataType"), null);
                if (asked != null && TYPES.contains(asked.trim().toUpperCase(Locale.ROOT))) {
                    type = asked.trim().toUpperCase(Locale.ROOT);
                }
            }
            // A lookup needs a code list nobody has defined for a scratch layer,
            // so the one type that cannot be adopted from a file is refused here
            // rather than left to produce an attribute with a dangling set.
            if ("LOOKUP".equals(type)) type = "STRING";

            insert(layerId, ds, header, storage, type, null, null, "NONE", false,
                    "CUSTOM", sort, header);
            sort += 10;
            made++;
            // A file with more columns than this is a spreadsheet, not a layer;
            // the cap keeps one pathological upload from filling the registry.
            if (made >= 300) break;
        }
        return made;
    }

    /** Whatever carries a placement role is mandatory by definition. */
    private void markPlacementMandatory(int layerId, String dataset) {
        jdbc.update("UPDATE layer_attribute SET mandatory = true "
                + "WHERE layer_id = ? AND dataset_key = ? AND role <> 'NONE'", layerId, dataset);
    }

    /**
     * Recognise the placement columns among names that already exist.
     *
     * The alias lists mirror the ones {@code AssetController} matches on at
     * import, so a layer seeded from live data ends up with the same columns
     * marked that the importer would actually have used.
     */
    private String roleFor(String col, String placement, String geometry) {
        if (!"LINEAR_REFERENCE".equals(placement)) return "NONE";
        String c = col.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (c.startsWith("sectionla") || c.equals("sectionlabel") || c.equals("section")
                || c.equals("label")) {
            return "SECTION_LABEL";
        }
        boolean point = "POINT".equals(geometry);
        if (point && (c.equals("chainage") || c.equals("chiange"))) return "CHAINAGE";
        if (!point) {
            if (c.startsWith("startch") || c.startsWith("fromch") || c.equals("from")
                    || c.equals("rdstrcha")) {
                return "START_CHAINAGE";
            }
            if (c.startsWith("endch") || c.startsWith("toch") || c.equals("to")
                    || c.equals("rdendcha")) {
                return "END_CHAINAGE";
            }
        }
        return "NONE";
    }

    /* ------------------------------------------------------------------
       Reads
       ------------------------------------------------------------------ */

    /** Every dataset of one layer, each with its attributes, for the UI. */
    public Map<String, Object> forLayer(int layerId) {
        Map<String, Object> layer = jdbc.queryForMap(
                "SELECT id, layer_key, name, source_type, placement, geometry_type, "
              + "attribute_mapping FROM layer_definition WHERE id = ?", layerId);

        String sourceType = String.valueOf(layer.get("source_type"));

        List<String> datasets = jdbc.queryForList(
                "SELECT DISTINCT dataset_key FROM layer_attribute WHERE layer_id = ? "
              + "ORDER BY dataset_key", String.class, layerId);
        if (datasets.isEmpty()) datasets = List.of("default");

        List<Map<String, Object>> out = new ArrayList<>();
        for (String ds : datasets) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("key", ds);
            d.put("label", datasetLabel(String.valueOf(layer.get("layer_key")), ds));
            d.put("attributes", attributesOf(layerId, ds));
            out.add(d);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layerId", layerId);
        m.put("layerName", layer.get("name"));
        m.put("sourceType", sourceType);
        m.put("placement", layer.get("placement"));
        m.put("geometryType", layer.get("geometry_type"));
        m.put("attributeMapping", layer.get("attribute_mapping"));
        /* Every attribute of every layer is editable, including the standard
           ones of a core layer. The protection that used to live here was a
           guess at which fields the RMMS cell would want fixed; they are the
           ones who know, so the screen is opened up and the rules are theirs to
           set once the real column list has been through their hands.

           Renaming stays safe regardless of what they change, because a label
           and its storage are separate: `name` is what the map cards, the
           dashboards and the import screen show, while `storage_key` is the
           column or jsonb key the value actually lives in and is never moved by
           a rename on a core layer.

           Deleting a standard attribute retires it rather than dropping the row
           — see deleteAttribute() for why. */
        m.put("canDeleteAttributes", true);
        m.put("canAddCustom", true);
        m.put("dateFormat", DATE_FORMAT);
        m.put("datasets", out);
        return m;
    }

    private String datasetLabel(String layerKey, String ds) {
        if ("counts".equals(ds)) return "Traffic Counts";
        if ("traffic_stations".equals(layerKey)) return "Stations";
        return "Attributes";
    }

    private List<Map<String, Object>> attributesOf(int layerId, String dataset) {
        return jdbc.query("""
            SELECT * FROM layer_attribute WHERE layer_id = ? AND dataset_key = ?
            ORDER BY sort_order, id
            """, (rs, i) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", rs.getInt("id"));
            a.put("name", rs.getString("name"));
            a.put("storageKey", rs.getString("storage_key"));
            a.put("dataType", rs.getString("data_type"));
            a.put("length", rs.getObject("length"));
            a.put("unit", rs.getString("unit"));
            a.put("dateFormat", rs.getString("date_format"));
            a.put("lookupKey", rs.getString("lookup_key"));
            a.put("mandatory", rs.getBoolean("mandatory"));
            a.put("role", rs.getString("role"));
            a.put("attributeType", rs.getString("attribute_type"));
            a.put("status", rs.getString("status"));
            // Counted as a vehicle at traffic-count import rather than
            // skipped as a meta column — see ensureSchema().
            a.put("vehicleCount", rs.getBoolean("vehicle_count"));
            // The header spellings an upload may use for this attribute. Shown
            // on the screen and editable, because the list only stays correct if
            // whoever receives a district's file can add the spelling it used.
            a.put("aliases", rs.getString("aliases"));
            // A placement attribute cannot be deleted or have its role cleared:
            // without it the layer becomes unimportable.
            a.put("placement", !"NONE".equals(rs.getString("role")));
            return a;
        }, layerId, dataset);
    }

    /* ------------------------------------------------------------------
       Writes
       ------------------------------------------------------------------ */

    @Transactional
    public Map<String, Object> addAttribute(int layerId, Map<String, Object> body) {
        String dataset = str(body.get("dataset"), "default");
        String name = require(str(body.get("name"), null), "Attribute name is required");
        String type = oneOf(str(body.get("dataType"), null), TYPES, "Data type");
        String storage = slug(name);
        if (storage.isEmpty()) throw new IllegalArgumentException("Attribute name must contain letters or digits");
        if (exists(layerId, dataset, storage)) {
            throw new IllegalArgumentException("An attribute named \"" + name + "\" already exists on this layer");
        }
        String role = oneOf(str(body.get("role"), "NONE"), ROLES, "Role");
        validateRoleType(role, type);
        if (!"NONE".equals(role)) assertRoleFree(layerId, dataset, role, 0);

        Integer id = insert(layerId, dataset, name.trim(), storage, type,
                intOf(body.get("length")), str(body.get("unit"), null),
                role, Boolean.TRUE.equals(body.get("mandatory")) || !"NONE".equals(role),
                "CUSTOM", 900, str(body.get("aliases"), null));
        if ("LOOKUP".equals(type)) {
            jdbc.update("UPDATE layer_attribute SET lookup_key = ? WHERE id = ?",
                    lookupKeyFor(body, name), id);
        }
        if (Boolean.TRUE.equals(body.get("vehicleCount"))) {
            jdbc.update("UPDATE layer_attribute SET vehicle_count = true WHERE id = ?", id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name.trim());
        return m;
    }

    /**
     * Edit an attribute, propagating a rename to the stored data.
     *
     * "Reflect everywhere" is the requirement, and for a jsonb-backed layer that
     * is literal: the key inside every row's {@code attrs} is rewritten in the
     * same transaction as the registry row, so nothing can observe the old name
     * afterwards. Column-backed layers ({@code roads}, {@code condition}) keep
     * their physical column and change only the label shown — renaming a real
     * column would break the 16 modules that select it by name.
     */
    @Transactional
    public void updateAttribute(int attrId, Map<String, Object> body) {
        Map<String, Object> cur = jdbc.queryForMap(
                "SELECT a.*, d.source_type, d.physical_table, d.source_table, d.layer_key "
              + "FROM layer_attribute a JOIN layer_definition d ON d.id = a.layer_id "
              + "WHERE a.id = ?", attrId);

        int layerId = (Integer) cur.get("layer_id");
        String dataset = String.valueOf(cur.get("dataset_key"));
        String oldKey = String.valueOf(cur.get("storage_key"));
        String oldRole = String.valueOf(cur.get("role"));

        String name = require(str(body.get("name"), null), "Attribute name is required");
        String type = oneOf(str(body.get("dataType"), String.valueOf(cur.get("data_type"))),
                TYPES, "Data type");
        String role = oneOf(str(body.get("role"), oldRole), ROLES, "Role");
        validateRoleType(role, type);

        // Clearing the role off a placement attribute would leave the layer
        // unable to place anything, so it is refused unless another attribute
        // has already taken the role over.
        if (!"NONE".equals(oldRole) && "NONE".equals(role)) {
            throw new IllegalArgumentException(
                    "\"" + cur.get("name") + "\" is what places this layer's features ("
                    + roleLabel(oldRole) + "). Give the role to another attribute first.");
        }
        if (!"NONE".equals(role)) assertRoleFree(layerId, dataset, role, attrId);

        boolean mandatory = !"NONE".equals(role) || Boolean.TRUE.equals(body.get("mandatory"));
        boolean vehicleCount = body.containsKey("vehicleCount")
                ? Boolean.TRUE.equals(body.get("vehicleCount"))
                : Boolean.TRUE.equals(cur.get("vehicle_count"));

        jdbc.update("""
            UPDATE layer_attribute
               SET name = ?, data_type = ?, length = ?, unit = ?, role = ?, mandatory = ?,
                   lookup_key = ?, status = ?, aliases = ?, vehicle_count = ?
             WHERE id = ?
            """, name.trim(), type, intOf(body.get("length")), str(body.get("unit"), null),
            role, mandatory,
            "LOOKUP".equals(type) ? lookupKeyFor(body, name) : null,
            str(body.get("status"), "ACTIVE"),
            str(body.get("aliases"), str(cur.get("aliases"), null)), vehicleCount, attrId);

        /* Only a CUSTOM attribute on a jsonb-backed layer owns its storage key,
           so only that case renames stored data. A STANDARD attribute keeps its
           column or jsonb key whatever it is renamed to, which is the property
           that makes renaming safe: every dashboard, report and card reads the
           storage key (the FWD dashboard matches attrs keys against 'd0',
           'airtemp' and so on; the condition dashboards select real columns),
           and none of them has ever read the label. Move the label and they do
           not notice; move the storage and they all break at once. */
        String newKey = slug(name);
        if (!newKey.equals(oldKey) && "CUSTOM".equals(String.valueOf(cur.get("attribute_type")))) {
            String table = jsonbTableFor(cur);
            /* Normalised, not exact. road_assets is one shared bag, and the
               readers above match keys with punctuation and case stripped — so a
               custom attribute renamed to "D 0" would slug to "d_0", collide
               with the standard "D0" under that comparison, and start feeding
               the deflection dashboard. Refusing the storage move (the label
               still changes) keeps a rename incapable of reaching a reader. */
            if (table != null && keyOwner(layerId, dataset, newKey, attrId) == null) {
                renameJsonbKey(table, cur.get("layer_key"), oldKey, newKey);
                jdbc.update("UPDATE layer_attribute SET storage_key = ? WHERE id = ?", newKey, attrId);
            }
        }
    }

    /** The id of another attribute whose storage key collides with {@code key}, or null. */
    private Integer keyOwner(int layerId, String dataset, String key, int exceptId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, storage_key FROM layer_attribute "
              + "WHERE layer_id = ? AND dataset_key = ? AND id <> ?", layerId, dataset, exceptId);
        String want = norm(key);
        for (Map<String, Object> r : rows) {
            if (want.equals(norm(String.valueOf(r.get("storage_key"))))) {
                return (Integer) r.get("id");
            }
        }
        return null;
    }

    /**
     * Remove an attribute from the layer.
     *
     * A CUSTOM attribute is dropped outright — nothing re-creates it, so the row
     * can go. A STANDARD one is RETIRED instead: it is declared in
     * {@link LayerAttributeCatalog} or discovered from live data, so the next
     * boot's seed would simply put it back, and a delete that quietly undoes
     * itself on restart is worse than one that says what it did. Retired means
     * the same thing everywhere it matters — {@link #importSpec} drops it, so it
     * is no longer mapped at import, and the screen greys it out — and it is
     * reversible by setting the status back to Active.
     */
    @Transactional
    public void deleteAttribute(int attrId) {
        Map<String, Object> cur = jdbc.queryForMap(
                "SELECT a.role, a.name, a.attribute_type, d.source_type "
              + "FROM layer_attribute a JOIN layer_definition d ON d.id = a.layer_id "
              + "WHERE a.id = ?", attrId);

        // The one attribute that cannot go: without it the layer has no way to
        // place a feature, so the import it is removed from would fail outright.
        if (!"NONE".equals(String.valueOf(cur.get("role")))) {
            throw new ProtectedAttributeException(
                    "\"" + cur.get("name") + "\" places this layer's features ("
                    + roleLabel(String.valueOf(cur.get("role")))
                    + "). Give the role to another attribute first, then remove it.");
        }
        if ("CUSTOM".equals(String.valueOf(cur.get("attribute_type")))) {
            jdbc.update("DELETE FROM layer_attribute WHERE id = ?", attrId);
        } else {
            jdbc.update("UPDATE layer_attribute SET status = 'RETIRED' WHERE id = ?", attrId);
        }
    }

    /* ------------------------------------------------------------------
       Import mapping support
       ------------------------------------------------------------------ */

    /**
     * What the import screen needs to map a file's columns onto this layer.
     *
     * Returned rather than computed at import time so the mapping UI and the
     * importer agree on one definition of "required" — the rule that a
     * mandatory attribute must be matched, and a non-mandatory one may be
     * skipped, lives here and nowhere else.
     */
    public List<Map<String, Object>> importSpec(int layerId, String dataset) {
        return jdbc.query("""
            SELECT name, storage_key, data_type, mandatory, role, lookup_key, date_format,
                   unit, aliases
              FROM layer_attribute
             WHERE layer_id = ? AND dataset_key = ? AND status = 'ACTIVE'
             ORDER BY sort_order, id
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString("name"));
            m.put("storageKey", rs.getString("storage_key"));
            m.put("dataType", rs.getString("data_type"));
            m.put("mandatory", rs.getBoolean("mandatory"));
            m.put("role", rs.getString("role"));
            m.put("lookupKey", rs.getString("lookup_key"));
            m.put("dateFormat", rs.getString("date_format"));
            m.put("unit", rs.getString("unit"));
            m.put("aliases", rs.getString("aliases"));
            return m;
        }, layerId, dataset == null ? "default" : dataset);
    }

    /* ------------------------------------------------------------------
       Import header resolution
       ------------------------------------------------------------------ */

    /**
     * The importer dataset keys of {@link ImportTemplateController} mapped onto
     * the layer and dataset that declares their columns.
     *
     * Two names for one thing is the situation this removes. The import screen
     * has always spoken in dataset keys ("bridge", "traffic_counts") while the
     * registry speaks in layer keys, and each kept its own idea of what columns
     * that dataset has — the seeded bridge template listed six, the catalogue
     * declares seventeen. The mapping window checked a file against the six, so
     * a perfectly good return had eleven of its columns reported as "extra".
     * With this table the screen and Attribute Data answer from one list.
     *
     * {@code video_catalog} is absent on purpose: it is a list of files to
     * attach, not a map layer, so it has no attributes and keeps its template.
     *
     * The two administrative boundaries are here even though they have no CSV
     * importer: their upload is a shapefile whose fields are mapped onto these
     * same attributes in the Console, and the point of mapping a field by hand
     * is that the next file from the same source does not need it done again —
     * which is this table's job, not the file format's.
     */
    private static final Map<String, String[]> IMPORT_DATASETS = Map.ofEntries(
            Map.entry("condition",        new String[]{"condition", "default"}),
            Map.entry("bridge",           new String[]{"bridge", "default"}),
            Map.entry("culvert",          new String[]{"culvert", "default"}),
            Map.entry("furniture_line",   new String[]{"furniture_line", "default"}),
            Map.entry("furniture_point",  new String[]{"furniture_point", "default"}),
            Map.entry("subgrade",         new String[]{"subgrade", "default"}),
            Map.entry("bituminous_core",  new String[]{"bituminous_core", "default"}),
            Map.entry("pavement_crust",   new String[]{"pavement_crust", "default"}),
            Map.entry("fwd",              new String[]{"fwd", "default"}),
            Map.entry("traffic_stations", new String[]{"traffic_stations", "default"}),
            Map.entry("traffic_counts",   new String[]{"traffic_stations", "counts"}),
            Map.entry("boundary_district",     new String[]{"boundary_district", "default"}),
            Map.entry("boundary_constituency", new String[]{"boundary_constituency", "default"}));

    /** The layer and dataset behind an import dataset key, or null if it has none. */
    public String[] layerForDataset(String datasetKey) {
        return IMPORT_DATASETS.get(datasetKey);
    }

    /**
     * Record the column names someone mapped by hand as accepted spellings.
     *
     * Automatic matching only reaches a header that resembles the attribute or
     * one of its known spellings. A district that calls the section "Rd Ref" is
     * beyond any guess, so the import screen lets it be mapped by hand — and
     * this is what stops that being a chore repeated for every file they ever
     * send: the mapping made once becomes an alias, and the next file matches on
     * its own.
     *
     * Additive and idempotent. An alias already held, in any spelling that
     * normalises the same, is not added twice, and nothing is ever removed —
     * removing one is an edit, and edits belong on the Attribute Data screen
     * where they can be seen.
     *
     * @param byField attribute LABEL -> the file's column name
     * @return how many attributes gained a spelling
     */
    @Transactional
    public int learnAliases(String datasetKey, Map<String, String> byField) {
        String[] target = IMPORT_DATASETS.get(datasetKey);
        if (target == null || byField == null || byField.isEmpty()) return 0;

        int learned = 0;
        for (Map.Entry<String, String> e : byField.entrySet()) {
            String label = e.getKey(), column = e.getValue();
            if (label == null || column == null || column.isBlank()) continue;

            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.aliases FROM layer_attribute a
                  JOIN layer_definition d ON d.id = a.layer_id
                 WHERE d.layer_key = ? AND a.dataset_key = ? AND a.name = ?
                 LIMIT 1
                """, target[0], target[1], label);
            if (rows.isEmpty()) continue;

            String current = str(rows.get(0).get("aliases"), "");
            boolean held = false;
            for (String a : current.split(",")) {
                if (norm(a).equals(norm(column))) { held = true; break; }
            }
            if (held) continue;

            String updated = current.isBlank() ? column.trim() : current + "," + column.trim();
            jdbc.update("UPDATE layer_attribute SET aliases = ? WHERE id = ?",
                    updated, rows.get(0).get("id"));
            learned++;
            log.info("Learned column name \"{}\" for attribute \"{}\" on {}",
                    column.trim(), label, datasetKey);
        }
        return learned;
    }

    /**
     * One import dataset's columns, shaped the way the mapping window already
     * consumes {@code import_template_columns}.
     *
     * Returned in that shape deliberately: the validator's matching, cell-type
     * checking and error reporting are all fine as they are and did not need to
     * change — only where the column list comes from did. Anything the registry
     * cannot answer for falls back to the stored template.
     *
     * Retired attributes are excluded, so retiring one on the Attribute Data
     * screen really does stop it being mapped at import.
     */
    public List<Map<String, Object>> importColumns(String datasetKey) {
        String[] target = IMPORT_DATASETS.get(datasetKey);
        if (target == null) return List.of();
        try {
            return jdbc.query("""
                SELECT a.name, a.storage_key, a.data_type, a.mandatory, a.aliases, a.lookup_key
                  FROM layer_attribute a
                  JOIN layer_definition d ON d.id = a.layer_id
                 WHERE d.layer_key = ? AND a.dataset_key = ? AND a.status = 'ACTIVE'
                 ORDER BY a.sort_order, a.id
                """, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                // field_name is the LABEL, not the storage key: it is what the
                // screen shows and what the header is rewritten to, and the
                // importers resolve either one anyway.
                m.put("field_name", rs.getString("name"));
                m.put("csv_column", rs.getString("name"));
                m.put("storage_key", rs.getString("storage_key"));
                m.put("data_type", switch (String.valueOf(rs.getString("data_type"))) {
                    case "DECIMAL", "INTEGER" -> "number";
                    case "DATE" -> "date";
                    default -> "text";
                });
                m.put("required", rs.getBoolean("mandatory"));
                m.put("aliases", rs.getString("aliases"));
                // Carried so the validator can enforce a coded column: a LOOKUP
                // attribute permits its codes and values and nothing else.
                m.put("lookup_key", rs.getString("lookup_key"));
                return m;
            }, target[0], target[1]);
        } catch (Exception e) {
            log.warn("Could not read import columns for dataset {} — the stored template "
                    + "will be used instead: {}", datasetKey, e.toString());
            return List.of();
        }
    }

    /**
     * Build the thing an importer needs to read a district's file: a map from
     * whatever the header says to the storage key this layer keeps that field
     * under, plus which header carries each placement role.
     *
     * Read once per upload rather than per row or per column — the alias lists
     * are small and fixed for the duration of a file, and a per-cell lookup
     * would put a query inside the parse loop.
     *
     * Returns an EMPTY resolver, never null, if the layer has no attributes or
     * the registry failed to initialise. Every caller then falls back to its own
     * hard-coded alias list, so an import can still run on a database where
     * Layer Management never came up.
     */
    public HeaderResolver headerResolver(String layerKey, String dataset) {
        Map<String, String> byHeader = new LinkedHashMap<>();
        Map<String, String> labelByKey = new LinkedHashMap<>();
        Map<String, String> byRole = new LinkedHashMap<>();
        try {
            jdbc.query("""
                SELECT a.name, a.storage_key, a.role, a.aliases
                  FROM layer_attribute a
                  JOIN layer_definition d ON d.id = a.layer_id
                 WHERE d.layer_key = ? AND a.dataset_key = ? AND a.status = 'ACTIVE'
                 ORDER BY a.sort_order, a.id
                """, rs -> {
                String storage = rs.getString("storage_key");
                String role = rs.getString("role");
                if (role != null && !"NONE".equals(role)) byRole.putIfAbsent(role, storage);
                labelByKey.putIfAbsent(storage, rs.getString("name"));
                // Storage key and label first, then the aliases. putIfAbsent, so
                // the attribute that owns a spelling keeps it when a later one
                // lists the same string as an alias.
                byHeader.putIfAbsent(norm(storage), storage);
                byHeader.putIfAbsent(norm(rs.getString("name")), storage);
                String aliases = rs.getString("aliases");
                if (aliases != null) {
                    for (String alias : aliases.split(",")) {
                        if (!alias.isBlank()) byHeader.putIfAbsent(norm(alias), storage);
                    }
                }
            }, layerKey, dataset == null ? "default" : dataset);
        } catch (Exception e) {
            log.warn("Could not build the header resolver for layer {} — the importer will "
                    + "fall back to its built-in aliases: {}", layerKey, e.toString());
        }
        return new HeaderResolver(byHeader, labelByKey, byRole);
    }

    /**
     * One upload's header mapping.
     *
     * Deliberately immutable and free of a JdbcTemplate: an importer holds it
     * across a whole file, and nothing it answers should be able to change
     * halfway through parsing one.
     */
    public static final class HeaderResolver {

        private final Map<String, String> byHeader;
        private final Map<String, String> labelByKey;
        private final Map<String, String> byRole;

        HeaderResolver(Map<String, String> byHeader, Map<String, String> labelByKey,
                       Map<String, String> byRole) {
            this.byHeader = byHeader;
            this.labelByKey = labelByKey;
            this.byRole = byRole;
        }

        public boolean isEmpty() {
            return byHeader.isEmpty();
        }

        /**
         * The label of the attribute that owns {@code header}, or null.
         *
         * Needed by the column-backed importers, which look their fields up by
         * the label rather than the storage key — {@code condition} keeps
         * latitude in {@code start_lat} but the survey return, and therefore the
         * code reading it, calls the field {@code Start_Latitude}. Normalising
         * cannot bridge those two, so the label has to be indexed alongside.
         */
        public String labelFor(String header) {
            String key = keyFor(header);
            return key == null ? null : labelByKey.get(key);
        }

        /**
         * The storage key this layer keeps {@code header} under, or null if the
         * layer does not recognise it.
         *
         * Null is the honest answer and callers must handle it: a column nobody
         * declared is still worth storing under its own name, so the caller
         * keeps the raw header rather than dropping the value.
         */
        public String keyFor(String header) {
            return byHeader.get(norm(header));
        }

        /** The storage key of the attribute holding a placement role, or null. */
        public String roleKey(String role) {
            return byRole.get(role);
        }

        /**
         * The index of the column carrying a placement role, or null.
         *
         * Resolved through the alias list, which is the whole point: a district
         * that spells the section column differently is fixed by adding that
         * spelling in Attribute Data, with no code change and no re-import of
         * everyone else's files.
         */
        public Integer columnFor(String[] headers, String role) {
            String want = byRole.get(role);
            if (want == null || headers == null) return null;
            for (int i = 0; i < headers.length; i++) {
                if (want.equals(keyFor(headers[i]))) return i;
            }
            return null;
        }
    }

    /* ------------------------------------------------------------------
       The catalogue the frontend reads
       ------------------------------------------------------------------ */

    /**
     * Every layer's attribute labels, keyed the way the map data is keyed.
     *
     * One small response, fetched once when the viewer loads, so that the
     * inspection cards, the summary cards and the dashboards all print the name
     * the RMMS cell set in Attribute Data rather than each module's own guess at
     * what a column should be called. Keeping it to label / unit / type is what
     * keeps it small enough to fetch eagerly: nothing here is per-feature.
     *
     * Aliases are included so a card can resolve a value that a district's file
     * stored under an older spelling and still show it under the current label.
     * Retired attributes are included too, marked inactive — a stored row keeps
     * its value after its attribute is retired, and showing it as a raw jsonb key
     * would be worse than showing it under the name it used to have.
     */
    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT d.layer_key, a.dataset_key, a.name, a.storage_key, a.data_type,
                   a.unit, a.role, a.status, a.aliases, a.lookup_key, a.vehicle_count
              FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id
             ORDER BY d.layer_key, a.dataset_key, a.sort_order, a.id
            """, rs -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> layer = (Map<String, Object>) out.computeIfAbsent(
                    rs.getString("layer_key"), k -> new LinkedHashMap<String, Object>());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) layer.computeIfAbsent(
                    rs.getString("dataset_key"), k -> new ArrayList<Map<String, Object>>());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", rs.getString("name"));
            a.put("key", rs.getString("storage_key"));
            a.put("type", rs.getString("data_type"));
            a.put("unit", rs.getString("unit"));
            a.put("role", rs.getString("role"));
            a.put("active", "ACTIVE".equals(rs.getString("status")));
            a.put("aliases", rs.getString("aliases"));
            // The code list that decodes this attribute's values, if it has one.
            // Carried here so a card can expand a short code without a second
            // request per field — see AttrCatalog.expand().
            a.put("lookupKey", rs.getString("lookup_key"));
            // Read by index.html's trfAggregate() to tell a vehicle-type column
            // (Bike - Scooter, Auto Rickshaw, …) apart from a meta one (Station
            // Name, Date, Time, Direction) among this dataset's declared attributes.
            a.put("vehicleCount", rs.getBoolean("vehicle_count"));
            list.add(a);
        });
        return out;
    }

    /* ------------------------------------------------------------------
       Lookups
       ------------------------------------------------------------------ */

    public List<Map<String, Object>> lookupSets() {
        return jdbc.query("SELECT s.id, s.set_key, s.name, "
                + "(SELECT count(*) FROM lookup_value v WHERE v.set_id = s.id) AS values "
                + "FROM lookup_set s ORDER BY s.name", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("key", rs.getString("set_key"));
            m.put("name", rs.getString("name"));
            m.put("values", rs.getLong("values"));
            return m;
        });
    }

    /** Create the set on demand when an attribute is switched to Lookup. */
    private String lookupKeyFor(Map<String, Object> body, String attrName) {
        String given = str(body.get("lookupKey"), null);
        String key = (given == null || given.isBlank()) ? slug(attrName) : slug(given);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM lookup_set WHERE set_key = ?",
                Integer.class, key);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO lookup_set (set_key, name) VALUES (?, ?)", key, attrName.trim());
        }
        return key;
    }

    /* ------------------------------------------------------------------
       Helpers
       ------------------------------------------------------------------ */

    /**
     * Insert one attribute, returning its id, or null if it already existed.
     *
     * Uses a query rather than {@code queryForObject} because {@code ON CONFLICT
     * DO NOTHING} legitimately returns no row — that is the ordinary case on a
     * re-seed, not an error worth throwing over.
     */
    private Integer insert(int layerId, String dataset, String name, String storage,
                           String type, Integer length, String unit, String role,
                           boolean mandatory, String attrType, int sort) {
        return insert(layerId, dataset, name, storage, type, length, unit, role,
                mandatory, attrType, sort, null);
    }

    /**
     * @param aliases the header spellings an upload may use, comma separated, or
     *                null for an attribute nobody uploads into. Doubles as the
     *                "this label came from the catalogue" marker: when it is set,
     *                {@code seeded_name} records the label so a later correction
     *                can be applied without overwriting a rename.
     */
    private Integer insert(int layerId, String dataset, String name, String storage,
                           String type, Integer length, String unit, String role,
                           boolean mandatory, String attrType, int sort, String aliases) {
        List<Integer> ids = jdbc.queryForList("""
            INSERT INTO layer_attribute
                (layer_id, dataset_key, name, storage_key, data_type, length, unit,
                 date_format, role, mandatory, attribute_type, sort_order, aliases, seeded_name)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (layer_id, dataset_key, storage_key) DO NOTHING
            RETURNING id
            """, Integer.class,
            layerId, dataset, name, storage, type, length, unit,
            "DATE".equals(type) ? DATE_FORMAT : null, role, mandatory, attrType, sort,
            aliases, aliases == null ? null : name);
        if (!ids.isEmpty()) return ids.get(0);

        /* The row already existed. Re-seeding is where a shipped correction has
           to land: the alias list is always refreshed (it is reference data, and
           a spelling the catalogue learns about helps whatever the attribute is
           now called), and everything the RMMS cell can see or edit — label,
           unit, order, type, role — only while the attribute is still exactly as
           the catalogue left it.

           The order matters as much as the label. The discovery seed that ran
           before this file listed a layer's fields alphabetically, so subgrade
           opened on "CBR" and FWD on "D0" rather than on the section label that
           identifies the row. Those rows carry low sort values, so refreshing
           only the ones the catalogue itself appended would leave every existing
           database stuck in alphabetical order forever.

           The type matters as much as the label. A latitude discovered from
           jsonb was typed String, because a bare key carries no type and the
           name gives nothing away — but declared it is a Decimal, and a chainage
           left as String would fail validateRoleType() the first time anyone
           edited it. So the correction has to reach existing rows, not only new
           ones, or every database that predates this file keeps the wrong type
           forever. */
        if (aliases != null) {
            // Every SET reads the row as it was before this statement, so the
            // repeated "untouched" test is one consistent decision across all of
            // them rather than five that could disagree.
            String untouched = "seeded_name IS NULL OR seeded_name = name";
            jdbc.update("""
                UPDATE layer_attribute
                   SET aliases = ?,
                       unit        = CASE WHEN %1$s THEN COALESCE(?, unit) ELSE unit END,
                       sort_order  = CASE WHEN %1$s THEN ? ELSE sort_order END,
                       name        = CASE WHEN %1$s THEN ? ELSE name        END,
                       -- NEVER downgrades a LOOKUP. The catalogue declares these
                       -- columns STRING because that is what they hold; the
                       -- Lookup module then types them LOOKUP to switch the code
                       -- list on. Without this guard the two fight every boot:
                       -- the catalogue resets the type, bind() will not redo it
                       -- (the attribute is already bound), and every lookup in
                       -- the system goes quietly dead on the first restart.
                       data_type   = CASE WHEN %1$s AND data_type <> 'LOOKUP'
                                          THEN ? ELSE data_type END,
                       role        = CASE WHEN %1$s THEN ? ELSE role        END,
                       mandatory   = CASE WHEN %1$s THEN ? ELSE mandatory   END,
                       date_format = CASE WHEN %1$s THEN ? ELSE date_format END,
                       seeded_name = ?
                 WHERE layer_id = ? AND dataset_key = ? AND storage_key = ?
                """.formatted(untouched),
                aliases, unit, sort, name, type, role, mandatory,
                "DATE".equals(type) ? DATE_FORMAT : null, name,
                layerId, dataset, storage);
        }
        return null;
    }

    /**
     * Move every row's value from one {@code attrs} key to another.
     *
     * Written with {@code jsonb_exists()} rather than the {@code ?} containment
     * operator on purpose: {@code ?} is also JDBC's parameter placeholder, so
     * the operator form would be parsed as a bind variable and the statement
     * would fail at runtime.
     */
    private void renameJsonbKey(String table, Object layerKey, String oldKey, String newKey) {
        if (!SAFE_TABLE.matcher(table).matches()) return;
        boolean shared = "road_assets".equals(table);
        String sql = "UPDATE " + table
                   + " SET attrs = (attrs - ?) || jsonb_build_object(?, attrs -> ?)"
                   + " WHERE jsonb_exists(attrs, ?)"
                   + (shared ? " AND asset_type = ?" : "");
        if (shared) {
            jdbc.update(sql, oldKey, newKey, oldKey, oldKey, String.valueOf(layerKey));
        } else {
            jdbc.update(sql, oldKey, newKey, oldKey, oldKey);
        }
    }

    /** The table whose {@code attrs} bag holds this layer's custom values, if any. */
    private String jsonbTableFor(Map<String, Object> cur) {
        Object physical = cur.get("physical_table");
        if (physical != null) return String.valueOf(physical);
        Object source = cur.get("source_table");
        return "road_assets".equals(String.valueOf(source)) ? "road_assets" : null;
    }

    private void assertRoleFree(int layerId, String dataset, String role, int exceptId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM layer_attribute WHERE layer_id = ? AND dataset_key = ? "
              + "AND role = ? AND id <> ?", Integer.class, layerId, dataset, role, exceptId);
        if (n != null && n > 0) {
            throw new IllegalArgumentException(
                    "Another attribute is already the " + roleLabel(role)
                    + " for this layer. Only one attribute can hold that role.");
        }
    }

    /** A chainage must be a number and a section label must be text. */
    private void validateRoleType(String role, String type) {
        if ("SECTION_LABEL".equals(role) && !"STRING".equals(type)) {
            throw new IllegalArgumentException("The section label must be a String attribute");
        }
        if (role.endsWith("CHAINAGE") && !("DECIMAL".equals(type) || "INTEGER".equals(type))) {
            throw new IllegalArgumentException("A chainage must be a Decimal or Integer attribute");
        }
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "SECTION_LABEL" -> "section label";
            case "CHAINAGE" -> "chainage";
            case "START_CHAINAGE" -> "from-chainage";
            case "END_CHAINAGE" -> "to-chainage";
            default -> "placement";
        };
    }

    private boolean hasRole(int layerId, String dataset, String role) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM layer_attribute "
                + "WHERE layer_id = ? AND dataset_key = ? AND role = ?",
                Integer.class, layerId, dataset, role);
        return n != null && n > 0;
    }

    /**
     * The storage key of the attribute that claims {@code header} as one of its
     * spellings, or null if none does.
     *
     * Matching is on the normalised form ({@link #norm}) so case, spaces,
     * underscores and punctuation do not have to agree — "Section_Label",
     * "Section Label" and "section label" are one header, which is precisely the
     * divergence between districts this exists to absorb.
     */
    String aliasOwner(int layerId, String dataset, String header) {
        String want = norm(header);
        if (want.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT storage_key, name, aliases FROM layer_attribute "
              + "WHERE layer_id = ? AND dataset_key = ? AND status = 'ACTIVE' "
              + "ORDER BY sort_order, id", layerId, dataset);
        for (Map<String, Object> r : rows) {
            String storage = String.valueOf(r.get("storage_key"));
            if (want.equals(norm(storage)) || want.equals(norm(String.valueOf(r.get("name"))))) {
                return storage;
            }
            Object aliases = r.get("aliases");
            if (aliases == null) continue;
            for (String alias : String.valueOf(aliases).split(",")) {
                if (want.equals(norm(alias))) return storage;
            }
        }
        return null;
    }

    /** Case-, space- and punctuation-insensitive header form. */
    static String norm(String s) {
        return s == null ? "" : s.replace("﻿", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean exists(int layerId, String dataset, String storage) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM layer_attribute "
                + "WHERE layer_id = ? AND dataset_key = ? AND storage_key = ?",
                Integer.class, layerId, dataset, storage);
        return n != null && n > 0;
    }

    private long countFor(int layerId, String dataset) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM layer_attribute "
                + "WHERE layer_id = ? AND dataset_key = ?", Long.class, layerId, dataset);
        return n == null ? 0 : n;
    }

    private static String sqlTypeToAttrType(String sqlType) {
        return switch (sqlType) {
            case "bigint", "integer", "smallint" -> "INTEGER";
            case "numeric", "double precision", "real", "decimal" -> "DECIMAL";
            case "date", "timestamp without time zone", "timestamp with time zone" -> "DATE";
            case "boolean" -> "BOOLEAN";
            default -> "STRING";
        };
    }

    /** Best-effort typing for a jsonb key, which carries no declared type. */
    private static String guessTypeFromName(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("date")) return "DATE";
        if (k.contains("chainage") || k.contains("chiange")) return "DECIMAL";
        if (k.matches(".*\\b(d\\d+|cbr|mdd|omc|fdd|fmc|ll|pl|pi|thickness|density|width|length)\\b.*")) {
            return "DECIMAL";
        }
        return "STRING";
    }

    /** "section_label" / "Rd_Str_cha" -> "Section Label" / "Rd Str Cha". */
    private static String prettify(String col) {
        String s = col.replaceAll("[_-]+", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.length() > 1 ? w.substring(1) : "").append(' ');
        }
        return sb.toString().trim();
    }

    private static String slug(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String str(Object o, String dflt) {
        return (o == null || String.valueOf(o).isBlank()) ? dflt : String.valueOf(o);
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return (o == null || String.valueOf(o).isBlank()) ? null : Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String require(String s, String msg) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(msg);
        return s;
    }

    private static String oneOf(String s, Set<String> allowed, String what) {
        if (s != null && allowed.contains(s.trim().toUpperCase(Locale.ROOT))) {
            return s.trim().toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException(what + " must be one of " + String.join(", ", allowed));
    }

    /** Thrown when an attribute is protected from deletion. */
    public static class ProtectedAttributeException extends RuntimeException {
        public ProtectedAttributeException(String message) {
            super(message);
        }
    }
}
