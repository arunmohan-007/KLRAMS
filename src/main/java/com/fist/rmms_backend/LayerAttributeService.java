package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
     * Describe the attributes every existing layer already has.
     *
     * Only ever ADDS: an attribute the user has since renamed, marked mandatory
     * or retired is left exactly as they left it, because the seed matches on
     * {@code storage_key} and skips anything already present. That is what makes
     * this safe to re-run on every boot.
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
        if (countFor(layerId, "default") > 0) return;   // already described

        switch (key) {
            case "roads", "full_road_network", "condition", "traffic_stations" ->
                    seedFromColumns(layerId, table, placement, geometry);

            // Traffic carries TWO datasets: the stations themselves, and the
            // counts recorded at each. They are separate uploads with separate
            // shapes, so they get separate attribute sets on one layer.
            case "traffic_stations_counts" -> { /* handled below */ }

            case "fwd", "bridge", "culvert", "furniture_line", "furniture_point",
                 "subgrade", "bituminous_core", "pavement_crust" ->
                    seedFromAssetAttrs(layerId, key, placement, geometry);

            case "boundary_district", "boundary_constituency" -> seedBoundary(layerId);

            default -> {
                // System-generated layers describe what they expose, not what is
                // uploaded — there is no import target to map onto.
                if ("SYSTEM_GENERATED".equals(sourceType)) seedDerived(layerId, key);
            }
        }

        if ("traffic_stations".equals(key) && countFor(layerId, "counts") == 0) {
            seedTrafficCounts(layerId);
        }
    }

    /** Layers whose fields are real columns: read them from the catalogue. */
    private void seedFromColumns(int layerId, String table, String placement, String geometry) {
        if (table == null || !SAFE_TABLE.matcher(table).matches()) return;
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length "
              + "FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position", table);
        int sort = 10;
        for (Map<String, Object> c : cols) {
            String col = String.valueOf(c.get("column_name"));
            if (col.equals("id") || col.equals("geom") || col.equals("attrs")) continue;
            Integer len = (c.get("character_maximum_length") instanceof Number n) ? n.intValue() : null;
            String role = roleFor(col, placement, geometry);
            String type = role.endsWith("CHAINAGE") ? "DECIMAL"
                        : "SECTION_LABEL".equals(role) ? "STRING"
                        : sqlTypeToAttrType(String.valueOf(c.get("data_type")));
            insert(layerId, "default", prettify(col), col, type, len, null,
                    role, false, "STANDARD", sort);
            sort += 10;
        }
        markPlacementMandatory(layerId, "default");
    }

    /** road_assets-backed layers: the attribute names are the jsonb keys in use. */
    private void seedFromAssetAttrs(int layerId, String assetType, String placement, String geometry) {
        List<String> keys = jdbc.queryForList(
                "SELECT DISTINCT k FROM road_assets, jsonb_object_keys(attrs) AS k "
              + "WHERE asset_type = ? ORDER BY k", String.class, assetType);
        int sort = 10;
        for (String k : keys) {
            String role = roleFor(k, placement, geometry);
            // The role decides the type, not the name. FWD's chainage columns are
            // literally called "From" and "To", which no name-based guess reads as
            // numeric — and a chainage typed String would fail the very validation
            // this service applies when the attribute is later edited.
            String type = role.endsWith("CHAINAGE") ? "DECIMAL"
                        : "SECTION_LABEL".equals(role) ? "STRING"
                        : guessTypeFromName(k);
            insert(layerId, "default", k, k, type, null, null, role, false, "STANDARD", sort);
            sort += 10;
        }
        // A layer with no rows yet still needs its placement attributes, or it
        // could never be imported into in the first place.
        ensurePlacementAttributes(layerId, "default", placement, geometry);
        markPlacementMandatory(layerId, "default");
    }

    /** The second traffic dataset: the counts recorded at each station. */
    private void seedTrafficCounts(int layerId) {
        insert(layerId, "counts", "Station Name", "name", "STRING", 100, null,
                "NONE", true, "STANDARD", 10);
        insert(layerId, "counts", "Survey Date", "date", "DATE", null, null,
                "NONE", false, "STANDARD", 20);
        insert(layerId, "counts", "Vehicle Class", "vehicle_class", "STRING", 60, null,
                "NONE", false, "STANDARD", 30);
        insert(layerId, "counts", "Count", "count", "INTEGER", null, null,
                "NONE", false, "STANDARD", 40);
    }

    private void seedBoundary(int layerId) {
        insert(layerId, "default", "Boundary Type", "type", "STRING", 40, null,
                "NONE", true, "STANDARD", 10);
        insert(layerId, "default", "Name", "name", "STRING", 255, null,
                "NONE", false, "STANDARD", 20);
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
        boolean protectedLayer = !"USER".equals(sourceType);

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
        // Protected layers keep their standard attributes: custom ones may still
        // be added (that is additive and harmless), but nothing may be deleted.
        m.put("canDeleteAttributes", !protectedLayer);
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
                "CUSTOM", 900);
        if ("LOOKUP".equals(type)) {
            jdbc.update("UPDATE layer_attribute SET lookup_key = ? WHERE id = ?",
                    lookupKeyFor(body, name), id);
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

        jdbc.update("""
            UPDATE layer_attribute
               SET name = ?, data_type = ?, length = ?, unit = ?, role = ?, mandatory = ?,
                   lookup_key = ?, status = ?
             WHERE id = ?
            """, name.trim(), type, intOf(body.get("length")), str(body.get("unit"), null),
            role, mandatory,
            "LOOKUP".equals(type) ? lookupKeyFor(body, name) : null,
            str(body.get("status"), "ACTIVE"), attrId);

        // Only a CUSTOM attribute on a jsonb-backed layer owns its storage key,
        // so only that case renames stored data.
        String newKey = slug(name);
        if (!newKey.equals(oldKey) && "CUSTOM".equals(String.valueOf(cur.get("attribute_type")))) {
            String table = jsonbTableFor(cur);
            if (table != null && !exists(layerId, dataset, newKey)) {
                renameJsonbKey(table, cur.get("layer_key"), oldKey, newKey);
                jdbc.update("UPDATE layer_attribute SET storage_key = ? WHERE id = ?", newKey, attrId);
            }
        }
    }

    @Transactional
    public void deleteAttribute(int attrId) {
        Map<String, Object> cur = jdbc.queryForMap(
                "SELECT a.role, a.name, a.attribute_type, d.source_type "
              + "FROM layer_attribute a JOIN layer_definition d ON d.id = a.layer_id "
              + "WHERE a.id = ?", attrId);

        if (!"USER".equals(String.valueOf(cur.get("source_type")))
                && !"CUSTOM".equals(String.valueOf(cur.get("attribute_type")))) {
            throw new ProtectedAttributeException(
                    "\"" + cur.get("name") + "\" is a standard attribute of a protected layer. "
                    + "Standard attributes cannot be deleted — you can mark it inactive instead.");
        }
        if (!"NONE".equals(String.valueOf(cur.get("role")))) {
            throw new ProtectedAttributeException(
                    "\"" + cur.get("name") + "\" places this layer's features ("
                    + roleLabel(String.valueOf(cur.get("role")))
                    + ") and cannot be deleted.");
        }
        jdbc.update("DELETE FROM layer_attribute WHERE id = ?", attrId);
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
            SELECT name, storage_key, data_type, mandatory, role, lookup_key, date_format
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
            return m;
        }, layerId, dataset == null ? "default" : dataset);
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
        List<Integer> ids = jdbc.queryForList("""
            INSERT INTO layer_attribute
                (layer_id, dataset_key, name, storage_key, data_type, length, unit,
                 date_format, role, mandatory, attribute_type, sort_order)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (layer_id, dataset_key, storage_key) DO NOTHING
            RETURNING id
            """, Integer.class,
            layerId, dataset, name, storage, type, length, unit,
            "DATE".equals(type) ? DATE_FORMAT : null, role, mandatory, attrType, sort);
        return ids.isEmpty() ? null : ids.get(0);
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
