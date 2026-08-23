package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
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
import java.util.regex.Pattern;

/**
 * The server-side layer registry: one declared home for what every map layer
 * IS, as opposed to how it draws.
 *
 * The viewer already has a client-side registry (js/02b-layer-registry.js) that
 * describes draw order, sources and toggles. That file answers "how is this
 * painted". This one answers the questions Layer Management asks instead:
 * where do the features come from, how is each one placed on the map, what may
 * be uploaded into it, and may a user touch it at all.
 *
 * Deliberately additive: seeding describes tables that already exist and does
 * not create, alter or read them. Nothing here changes what the map draws, so
 * the 16 modules reading DATA/ROADS are untouched.
 *
 * <h2>Why source_type and not an "editable" flag</h2>
 * Editability is DERIVED from where a layer's features come from, never stored
 * separately, so the UI and the API cannot drift into disagreeing about what is
 * protected:
 *
 * <ul>
 *   <li>{@code BUILT_IN} — core PWD data with a dedicated import pipeline
 *       (road network, structures, traffic, soil/core/crust). Protected.</li>
 *   <li>{@code SYSTEM_GENERATED} — computed from other layers and rebuilt on
 *       demand (PCI, 2 km IRI, condition segments). Protected, and has no
 *       upload target at all: importing into one would be overwritten by the
 *       next rebuild.</li>
 *   <li>{@code EDITABLE_BUILT_IN} — administrative boundary, the one core layer
 *       the RMMS cell maintains by hand.</li>
 *   <li>{@code USER} — created through Layer Management. The only kind that may
 *       be renamed, re-configured or deleted.</li>
 * </ul>
 */
@Service
public class LayerRegistryService {

    private static final Logger log = LoggerFactory.getLogger(LayerRegistryService.class);

    /* Physical tables for user layers all carry this prefix. It is what makes
       "is this table ours to drop?" answerable without trusting the caller —
       see dropLayer(). Nothing else in the schema uses it. */
    static final String USER_TABLE_PREFIX = "ul_";

    /* A generated table name is only ever prefix + digits + underscore + slug,
       so this is a belt-and-braces check on a string we built ourselves before
       it is concatenated into DDL (which cannot be parameterised). */
    private static final Pattern SAFE_TABLE = Pattern.compile("^ul_[0-9]+_[a-z0-9_]{1,40}$");

    private final JdbcTemplate jdbc;

    public LayerRegistryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensure() {
        try {
            ensureSchema();
            seedBuiltIns();
        } catch (Exception e) {
            // A registry failure must never stop the app booting — the map and
            // every existing module work fine without Layer Management.
            log.error("Layer registry init failed — Layer Management may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_folder (
                id          serial PRIMARY KEY,
                folder_key  text UNIQUE NOT NULL,
                name        text NOT NULL,
                sort_order  integer NOT NULL DEFAULT 100,
                system_folder boolean NOT NULL DEFAULT false,
                created_at  timestamp NOT NULL DEFAULT now()
            )""");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_definition (
                id            serial PRIMARY KEY,
                layer_key     text UNIQUE NOT NULL,
                folder_id     integer NOT NULL REFERENCES layer_folder(id),
                name          text NOT NULL,
                geometry_type text NOT NULL,
                placement     text NOT NULL,
                source_type   text NOT NULL,
                upload_formats text,
                attribute_mapping boolean NOT NULL DEFAULT false,
                physical_table text,
                source_table   text,
                derived_from   text,
                section_field  text,
                chainage_field text,
                lat_field      text,
                lng_field      text,
                notes          text,
                sort_order     integer NOT NULL DEFAULT 100,
                created_at     timestamp NOT NULL DEFAULT now(),
                created_by     text
            )""");

        jdbc.execute("CREATE INDEX IF NOT EXISTS layer_definition_folder_idx ON layer_definition(folder_id)");
    }

    /* ------------------------------------------------------------------
       Seeding
       ------------------------------------------------------------------ */

    /**
     * Describe the layers that already exist.
     *
     * Re-run on every boot and idempotent: built-in rows are upserted by
     * {@code layer_key} so a corrected description ships with a restart, while
     * USER layers are never touched by this pass.
     *
     * The folder list and its order are the seven groups the viewer's Layers
     * panel shows (map.html), so Layer Management and the map agree on where a
     * layer lives without either owning the other's markup.
     */
    private void seedBuiltIns() {
        folder("network",    "Road Network", 10);
        folder("condition",  "Road Condition Data & FWD", 20);
        folder("boundary",   "Administrative Boundary", 30);
        folder("structures", "Structures & Furniture", 40);
        folder("pci",        "PCI", 50);
        folder("traffic",    "Traffic Stations", 60);
        folder("geotech",    "Sub-Grade Soil, Bituminous Core & Pavement Crust", 70);

        /* ---- Road network ----
           Both are true geometry layers: features arrive already located, from
           a shapefile parsed in the browser (shpjs) and posted as GeoJSON. */
        Layer roads = new Layer("roads", "network", "Road Network");
        roads.geometry = "LINESTRING";
        roads.placement = "GEOMETRY";
        roads.sourceType = "BUILT_IN";
        roads.uploadFormats = "SHAPEFILE,GEOJSON";
        roads.sourceTable = "roads";
        roads.attributeMapping = true;
        roads.notes = "Road centrelines, one LineString per section. Every linear-referenced layer "
                + "(condition, FWD, traffic, structures) is placed against these by chainage, which "
                + "requires a single line per section — a multi-part road would make chainage "
                + "ambiguous, so this layer must stay LineString, never MultiLineString.";
        roads.sort = 10;
        seed(roads);

        Layer full = new Layer("full_road_network", "network", "Full Road Network");
        full.geometry = "MULTILINESTRING";
        full.placement = "GEOMETRY";
        full.sourceType = "BUILT_IN";
        full.uploadFormats = "SHAPEFILE,GEOJSON";
        full.sourceTable = "full_road_network";
        full.attributeMapping = true;
        full.notes = "Secondary network grouped by road name. Imported from the Layers panel.";
        full.sort = 20;
        seed(full);

        /* ---- Condition & FWD ----
           Two entries, because the folder really does hold two different things
           and collapsing them hides the import path. The RAW survey is a CSV
           import placed by section + chainage; the SEGMENTS the map paints are
           rebuilt from it. Describing only the segments would make the most
           frequently imported dataset in KLRAMS look un-importable. */
        Layer cond = new Layer("condition", "condition", "Road Condition Data");
        cond.geometry = "LINESTRING";
        cond.placement = "LINEAR_REFERENCE";
        cond.sourceType = "BUILT_IN";
        cond.uploadFormats = "CSV";
        cond.sourceTable = "condition";
        cond.attributeMapping = true;
        cond.sectionField = "section_label";
        cond.chainageField = "start_chainage / end_chainage";
        cond.notes = "The raw survey: IRI, cracking, pothole, rutting, texture, patchwork and "
                + "ravelling per lane (xsp). Placed by section + chainage; the CSV's lat/lng "
                + "columns are stored but not used for placement.";
        cond.sort = 10;
        seed(cond);

        Layer segs = new Layer("condition_segments", "condition", "Condition Segments");
        segs.geometry = "LINESTRING";
        segs.placement = "LINEAR_REFERENCE";
        segs.sourceType = "SYSTEM_GENERATED";
        segs.sourceTable = "condition_segments";
        segs.derivedFrom = "condition + roads";
        segs.sectionField = "section_label";
        segs.notes = "What the map actually paints: the raw survey cut onto the road centrelines "
                + "as per-lane segments. Rebuilt whenever condition data is imported.";
        segs.sort = 15;
        seed(segs);

        Layer fwd = new Layer("fwd", "condition", "FWD (Deflection)");
        fwd.geometry = "LINESTRING";
        fwd.placement = "LINEAR_REFERENCE";
        fwd.sourceType = "BUILT_IN";
        fwd.uploadFormats = "CSV";
        fwd.sourceTable = "road_assets";
        fwd.assetType = "fwd";
        fwd.attributeMapping = true;
        fwd.sectionField = "section_label";
        fwd.chainageField = "start_chainage / end_chainage";
        fwd.notes = "A From..To stretch with D0..Dn readings in attrs. Lat/lng in the CSV is "
                + "display-only — placement is by chainage.";
        fwd.sort = 20;
        seed(fwd);

        Layer iri = new Layer("iri_2km", "condition", "Avg IRI (2 km)");
        iri.geometry = "LINESTRING";
        iri.placement = "LINEAR_REFERENCE";
        iri.sourceType = "SYSTEM_GENERATED";
        iri.sourceTable = "iri_2km_segments";
        iri.derivedFrom = "condition_segments";
        iri.notes = "IRI rolled into 2 km bins per section: length-weighted average per lane "
                + "plus the worst of them. Rebuilt with the condition segments.";
        iri.sort = 30;
        seed(iri);

        /* ---- Administrative boundary ----
           The one editable core layer, and the one with the least edit-friendly
           storage: `boundary` is (type text PRIMARY KEY, geojson text), so a
           whole FeatureCollection is one row. There are no per-feature rows to
           edit — replacing a boundary means replacing the blob. */
        for (String[] b : new String[][]{{"district", "District Boundary", "10"},
                                          {"constituency", "Constituency Boundary", "20"}}) {
            Layer bl = new Layer("boundary_" + b[0], "boundary", b[1]);
            bl.geometry = "POLYGON";
            bl.placement = "GEOMETRY";
            bl.sourceType = "EDITABLE_BUILT_IN";
            bl.uploadFormats = "GEOJSON,SHAPEFILE";
            bl.sourceTable = "boundary";
            bl.boundaryType = b[0];
            bl.notes = "Stored as a single GeoJSON document keyed by type, not as per-feature "
                    + "rows. Replacing this layer replaces the whole document.";
            bl.sort = Integer.parseInt(b[2]);
            seed(bl);
        }

        /* ---- Structures & furniture ----
           All four live in road_assets, placed by section + chainage. Bridges
           and line furniture materialise a LINE via ST_LineSubstring; culverts
           and point furniture a POINT via ST_LineInterpolatePoint. */
        seedAsset("bridge",         "structures", "Bridges",            "LINESTRING", 10);
        seedAsset("culvert",        "structures", "Culverts",           "POINT",      20);
        seedAsset("furniture_line", "structures", "Furniture — Line",   "LINESTRING", 30);
        seedAsset("furniture_point","structures", "Furniture — Point",  "POINT",      40);

        /* ---- PCI ----
           Computed from the condition segments, never imported. */
        for (String[] p : new String[][]{{"pci_composite", "Composite PCI", "10"},
                                          {"pci_worst", "Worst-Lane PCI", "20"}}) {
            Layer pl = new Layer(p[0], "pci", p[1]);
            pl.geometry = "LINESTRING";
            pl.placement = "LINEAR_REFERENCE";
            pl.sourceType = "SYSTEM_GENERATED";
            pl.sourceTable = "condition_segments";
            pl.derivedFrom = "condition_segments";
            pl.notes = "Scored from the condition segments per IRC:82-2023. Rebuilt whenever "
                    + "condition data changes — it has no import target of its own.";
            pl.sort = Integer.parseInt(p[2]);
            seed(pl);
        }

        /* ---- Traffic stations ----
           The odd one out: traffic_stations has lat/lng COLUMNS but no geom,
           and placement ignores them. Position is computed live from section +
           chainage at request time, so an unmatched section is rejected at
           import rather than silently falling back to a coordinate. */
        Layer trf = new Layer("traffic_stations", "traffic", "Traffic Stations");
        trf.geometry = "POINT";
        trf.placement = "LINEAR_REFERENCE";
        trf.sourceType = "BUILT_IN";
        trf.uploadFormats = "CSV";
        trf.sourceTable = "traffic_stations";
        trf.attributeMapping = true;
        trf.sectionField = "section";
        trf.chainageField = "chainage";
        trf.notes = "Placed by chainage + section only; the lat/lng columns are not used for "
                + "placement. Position is computed live, never stored. A/B section pairs count "
                + "as one station in the dashboards.";
        trf.sort = 10;
        seed(trf);

        /* ---- Geotech ---- */
        seedAsset("subgrade",        "geotech", "Sub-Grade Soil",   "POINT", 10);
        seedAsset("bituminous_core", "geotech", "Bituminous Core",  "POINT", 20);
        seedAsset("pavement_crust",  "geotech", "Pavement Crust",   "POINT", 30);
    }

    /** road_assets-backed built-ins: same storage, same placement, same import format. */
    private void seedAsset(String assetType, String folder, String label, String geom, int sort) {
        Layer l = new Layer(assetType, folder, label);
        l.geometry = geom;
        l.placement = "LINEAR_REFERENCE";
        l.sourceType = "BUILT_IN";
        l.uploadFormats = "CSV";
        l.sourceTable = "road_assets";
        l.assetType = assetType;
        l.attributeMapping = true;
        l.sectionField = "section_label";
        l.chainageField = "POINT".equals(geom) ? "start_chainage" : "start_chainage / end_chainage";
        l.notes = "POINT".equals(geom)
                ? "Placed on the centreline at its chainage (ST_LineInterpolatePoint)."
                : "Placed as a stretch between its from/to chainages (ST_LineSubstring).";
        l.sort = sort;
        seed(l);
    }

    private void folder(String key, String name, int sort) {
        jdbc.update("""
            INSERT INTO layer_folder (folder_key, name, sort_order, system_folder)
            VALUES (?, ?, ?, true)
            ON CONFLICT (folder_key) DO UPDATE
               SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order
            """, key, name, sort);
    }

    /**
     * Upsert one built-in description.
     *
     * The WHERE guard is the load-bearing part: it makes re-seeding unable to
     * overwrite a USER layer that happens to share a key, so a user-created
     * layer can never be silently converted into a protected one.
     */
    private void seed(Layer l) {
        Integer folderId = folderId(l.folderKey);
        if (folderId == null) return;
        jdbc.update("""
            INSERT INTO layer_definition
                (layer_key, folder_id, name, geometry_type, placement, source_type,
                 upload_formats, attribute_mapping, source_table, derived_from,
                 section_field, chainage_field, notes, sort_order, created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'system')
            ON CONFLICT (layer_key) DO UPDATE
               SET folder_id = EXCLUDED.folder_id,
                   name = EXCLUDED.name,
                   geometry_type = EXCLUDED.geometry_type,
                   placement = EXCLUDED.placement,
                   source_type = EXCLUDED.source_type,
                   upload_formats = EXCLUDED.upload_formats,
                   attribute_mapping = EXCLUDED.attribute_mapping,
                   source_table = EXCLUDED.source_table,
                   derived_from = EXCLUDED.derived_from,
                   section_field = EXCLUDED.section_field,
                   chainage_field = EXCLUDED.chainage_field,
                   notes = EXCLUDED.notes,
                   sort_order = EXCLUDED.sort_order
             WHERE layer_definition.source_type <> 'USER'
            """,
            l.key, folderId, l.name, l.geometry, l.placement, l.sourceType,
            l.uploadFormats, l.attributeMapping, l.sourceTable, l.derivedFrom,
            l.sectionField, l.chainageField, l.notes, l.sort);
    }

    private Integer folderId(String key) {
        try {
            return jdbc.queryForObject("SELECT id FROM layer_folder WHERE folder_key = ?", Integer.class, key);
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------
       Reads
       ------------------------------------------------------------------ */

    /** Folders, each with its layers, in panel order — what the UI renders. */
    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> folders = jdbc.query(
                "SELECT id, folder_key, name, system_folder FROM layer_folder ORDER BY sort_order, name",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("key", rs.getString("folder_key"));
                    m.put("name", rs.getString("name"));
                    m.put("systemFolder", rs.getBoolean("system_folder"));
                    return m;
                });
        for (Map<String, Object> f : folders) {
            f.put("layers", layersIn((Integer) f.get("id")));
        }
        return folders;
    }

    private List<Map<String, Object>> layersIn(int folderId) {
        return jdbc.query("""
            SELECT * FROM layer_definition WHERE folder_id = ? ORDER BY sort_order, name
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String sourceType = rs.getString("source_type");
            m.put("id", rs.getInt("id"));
            m.put("key", rs.getString("layer_key"));
            m.put("name", rs.getString("name"));
            m.put("geometryType", rs.getString("geometry_type"));
            m.put("placement", rs.getString("placement"));
            m.put("sourceType", sourceType);
            m.put("uploadFormats", splitFormats(rs.getString("upload_formats")));
            m.put("attributeMapping", rs.getBoolean("attribute_mapping"));
            m.put("physicalTable", rs.getString("physical_table"));
            m.put("sourceTable", rs.getString("source_table"));
            m.put("derivedFrom", rs.getString("derived_from"));
            m.put("sectionField", rs.getString("section_field"));
            m.put("chainageField", rs.getString("chainage_field"));
            m.put("latField", rs.getString("lat_field"));
            m.put("lngField", rs.getString("lng_field"));
            m.put("notes", rs.getString("notes"));
            // Derived, never stored — see the class comment.
            m.put("editable", "USER".equals(sourceType) || "EDITABLE_BUILT_IN".equals(sourceType));
            m.put("deletable", "USER".equals(sourceType));
            m.put("importable", !"SYSTEM_GENERATED".equals(sourceType));
            m.put("features", featureCount(rs.getString("layer_key"), rs.getString("physical_table")));
            return m;
        }, folderId);
    }

    private List<String> splitFormats(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /**
     * Live feature count.
     *
     * Every branch is guarded to 0 rather than throwing: a layer whose table has
     * not been created yet (a fresh database, or an import that never ran) is a
     * normal state for this screen, not an error worth failing the whole tree
     * over.
     */
    private long featureCount(String layerKey, String physicalTable) {
        try {
            if (physicalTable != null && SAFE_TABLE.matcher(physicalTable).matches()) {
                return one("SELECT count(*) FROM " + physicalTable);
            }
            return switch (layerKey) {
                case "roads" -> one("SELECT count(*) FROM roads");
                case "full_road_network" -> one("SELECT count(*) FROM full_road_network");
                case "condition" -> one("SELECT count(*) FROM condition");
                case "condition_segments", "pci_composite", "pci_worst" ->
                        one("SELECT count(*) FROM condition_segments");
                case "iri_2km" -> one("SELECT count(*) FROM iri_2km_segments");
                case "traffic_stations" -> one("SELECT count(*) FROM traffic_stations");
                case "boundary_district" -> boundaryPresent("district");
                case "boundary_constituency" -> boundaryPresent("constituency");
                case "fwd", "bridge", "culvert", "furniture_line", "furniture_point",
                     "subgrade", "bituminous_core", "pavement_crust" ->
                        one("SELECT count(*) FROM road_assets WHERE asset_type = ?", layerKey);
                default -> 0L;
            };
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Boundaries are one blob per type, so "features" is 1 or 0 — present or not. */
    private long boundaryPresent(String type) {
        return one("SELECT count(*) FROM boundary WHERE type = ?", type);
    }

    private long one(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------
       Writes
       ------------------------------------------------------------------ */

    public Map<String, Object> createFolder(String name) {
        String clean = require(name, "Folder name is required");
        String key = slug(clean);
        if (key.isEmpty()) throw new IllegalArgumentException("Folder name must contain letters or digits");
        Integer existing = folderId(key);
        if (existing != null) throw new IllegalArgumentException("A folder named \"" + clean + "\" already exists");
        jdbc.update("INSERT INTO layer_folder (folder_key, name, sort_order, system_folder) VALUES (?,?,?,false)",
                key, clean, 500);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", folderId(key));
        m.put("key", key);
        m.put("name", clean);
        return m;
    }

    /**
     * Create a user layer: one registry row plus one physical table.
     *
     * The table is named entirely server-side — {@code ul_<id>_<slug>} — so the
     * name the user typed never reaches the DDL string. The id is allocated
     * first precisely so the generated name is unique without having to ask.
     *
     * Both statements share one transaction: a failed CREATE TABLE rolls the
     * registry row back with it, so a half-made layer cannot be left behind.
     */
    @Transactional
    public Map<String, Object> createLayer(Map<String, Object> body, String user) {
        String name = require(str(body.get("name")), "Layer name is required");
        int folderId = folderIdOrThrow(body.get("folderId"));
        String geometry = oneOf(str(body.get("geometryType")), "Geometry type",
                "POINT", "LINESTRING", "MULTILINESTRING", "POLYGON");
        String placement = oneOf(str(body.get("placement")), "Placement method",
                "GEOMETRY", "LATLNG", "LINEAR_REFERENCE");
        List<String> formats = formatsOf(body.get("uploadFormats"));
        boolean attrMapping = Boolean.TRUE.equals(body.get("attributeMapping"));

        validatePlacement(geometry, placement, formats);

        String key = uniqueLayerKey(slug(name));
        Integer id = jdbc.queryForObject("""
            INSERT INTO layer_definition
                (layer_key, folder_id, name, geometry_type, placement, source_type,
                 upload_formats, attribute_mapping, section_field, chainage_field,
                 lat_field, lng_field, sort_order, created_by)
            VALUES (?,?,?,?,?,'USER',?,?,?,?,?,?,500,?)
            RETURNING id
            """, Integer.class,
            key, folderId, name.trim(), geometry, placement,
            String.join(",", formats), attrMapping,
            "LINEAR_REFERENCE".equals(placement) ? str(body.get("sectionField")) : null,
            "LINEAR_REFERENCE".equals(placement) ? str(body.get("chainageField")) : null,
            "LATLNG".equals(placement) ? str(body.get("latField")) : null,
            "LATLNG".equals(placement) ? str(body.get("lngField")) : null,
            user);

        String table = USER_TABLE_PREFIX + id + "_" + slug(name);
        if (table.length() > 55) table = table.substring(0, 55);
        if (!SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Layer name does not yield a usable table name");
        }
        createPhysicalTable(table, geometry, placement);
        jdbc.update("UPDATE layer_definition SET physical_table = ? WHERE id = ?", table, id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("key", key);
        m.put("name", name.trim());
        m.put("physicalTable", table);
        m.put("attributeMapping", attrMapping);
        return m;
    }

    /**
     * The identity/geometry columns only.
     *
     * Attribute columns are deliberately NOT created here — they are the
     * Attribute Data module's job, added later against this table. What a layer
     * always needs is somewhere to put the shape and, for CSV placement, the
     * fields the shape is derived FROM: keeping section/chainage (or lat/lng)
     * as real columns means a re-placement after a road-network correction can
     * recompute geom without going back to the original file.
     */
    private void createPhysicalTable(String table, String geometry, String placement) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + table + " (\n");
        ddl.append("    id serial PRIMARY KEY,\n");
        ddl.append("    attrs jsonb,\n");
        if ("LINEAR_REFERENCE".equals(placement)) {
            ddl.append("    section_label text,\n");
            ddl.append("    start_chainage double precision,\n");
            ddl.append("    end_chainage double precision,\n");
        } else if ("LATLNG".equals(placement)) {
            ddl.append("    lat double precision,\n");
            ddl.append("    lng double precision,\n");
        }
        ddl.append("    geom geometry(").append(geometry).append(",4326),\n");
        ddl.append("    period_id integer,\n");
        ddl.append("    created_at timestamp NOT NULL DEFAULT now()\n)");
        jdbc.execute(ddl.toString());
        jdbc.execute("CREATE INDEX " + table + "_geom_idx ON " + table + " USING GIST (geom)");
        if ("LINEAR_REFERENCE".equals(placement)) {
            jdbc.execute("CREATE INDEX " + table + "_section_idx ON " + table + "(section_label)");
        }
    }

    /** Rename / re-file / re-configure. Only USER and EDITABLE_BUILT_IN layers qualify. */
    public void updateLayer(int id, Map<String, Object> body) {
        String sourceType = sourceTypeOf(id);
        if (!"USER".equals(sourceType) && !"EDITABLE_BUILT_IN".equals(sourceType)) {
            throw new ProtectedLayerException(sourceType);
        }
        String name = require(str(body.get("name")), "Layer name is required");
        if ("USER".equals(sourceType)) {
            int folderId = folderIdOrThrow(body.get("folderId"));
            List<String> formats = formatsOf(body.get("uploadFormats"));
            jdbc.update("UPDATE layer_definition SET name = ?, folder_id = ?, upload_formats = ? WHERE id = ?",
                    name.trim(), folderId, String.join(",", formats), id);
        } else {
            // A built-in's storage and placement are fixed by its pipeline; only
            // the label is the RMMS cell's to change.
            jdbc.update("UPDATE layer_definition SET name = ? WHERE id = ?", name.trim(), id);
        }
    }

    /**
     * Delete a user layer.
     *
     * Soft by default: the definition is retired but the physical table is left
     * in place, because runtime DDL plus an immediate DROP is an easy way to
     * lose uploaded data with no undo. {@code purge=true} is the deliberate
     * second step that actually drops it.
     */
    @Transactional
    public void deleteLayer(int id, boolean purge) {
        String sourceType = sourceTypeOf(id);
        if (!"USER".equals(sourceType)) {
            throw new ProtectedLayerException(sourceType);
        }
        String table = jdbc.queryForObject(
                "SELECT physical_table FROM layer_definition WHERE id = ?", String.class, id);
        jdbc.update("DELETE FROM layer_definition WHERE id = ?", id);
        if (purge && table != null) {
            // Only ever drops a table this service generated: the prefix check
            // makes an arbitrary table name unreachable from here even if the
            // registry row were tampered with.
            if (!SAFE_TABLE.matcher(table).matches()) {
                throw new IllegalArgumentException("Refusing to drop a table this registry did not create");
            }
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private String sourceTypeOf(int id) {
        try {
            return jdbc.queryForObject(
                    "SELECT source_type FROM layer_definition WHERE id = ?", String.class, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("No such layer");
        }
    }

    /* ------------------------------------------------------------------
       Validation helpers
       ------------------------------------------------------------------ */

    /**
     * Reject combinations that cannot actually place a feature.
     *
     * Caught here rather than at import time so the wizard fails while the user
     * is still describing the layer, instead of months later when someone
     * uploads a file into a layer that was never placeable.
     */
    private void validatePlacement(String geometry, String placement, List<String> formats) {
        if (formats.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one upload format");
        }
        boolean csvOnly = formats.size() == 1 && formats.contains("CSV");
        boolean hasGeoFile = formats.contains("SHAPEFILE") || formats.contains("GEOJSON");

        if ("GEOMETRY".equals(placement) && csvOnly) {
            throw new IllegalArgumentException(
                    "A CSV carries no geometry — choose lat/long or linear-reference placement, "
                    + "or accept a shapefile/GeoJSON as well");
        }
        if (!"GEOMETRY".equals(placement) && !formats.contains("CSV")) {
            throw new IllegalArgumentException(
                    "Lat/long and linear-reference placement describe how to read a CSV, "
                    + "so CSV must be an accepted format");
        }
        if ("LATLNG".equals(placement) && !"POINT".equals(geometry)) {
            throw new IllegalArgumentException(
                    "A lat/long pair can only place a point — use linear reference for lines");
        }
        if ("LINEAR_REFERENCE".equals(placement) && "POLYGON".equals(geometry)) {
            throw new IllegalArgumentException(
                    "A polygon cannot be placed by chainage — it needs a shapefile or GeoJSON");
        }
        if ("LINEAR_REFERENCE".equals(placement) && "MULTILINESTRING".equals(geometry)) {
            // A road with more than one part has no single chainage axis, so
            // "place this row at chainage X" is ambiguous — which part does X
            // belong to? roads.geom is kept as one LineString per section for
            // exactly this reason (see its seed entry above).
            throw new IllegalArgumentException(
                    "A multi-line feature has no single chainage axis, so it cannot be placed by "
                    + "linear reference — use Line instead, or place it from a shapefile/GeoJSON");
        }
        if (hasGeoFile && !"GEOMETRY".equals(placement)) {
            // Not fatal: a layer may accept both, taking geometry from the file
            // and computing it from a CSV. Recorded so the import module knows.
            log.debug("Layer accepts a geometry file but places CSV rows by {}", placement);
        }
    }

    private List<String> formatsOf(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = str(o);
                if (s == null) continue;
                s = s.trim().toUpperCase(Locale.ROOT);
                if (s.equals("SHAPEFILE") || s.equals("GEOJSON") || s.equals("CSV")) {
                    if (!out.contains(s)) out.add(s);
                }
            }
        }
        return out;
    }

    private int folderIdOrThrow(Object raw) {
        Integer id = (raw instanceof Number n) ? n.intValue() : null;
        if (id == null) throw new IllegalArgumentException("Choose a folder");
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM layer_folder WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) throw new IllegalArgumentException("No such folder");
        return id;
    }

    private String uniqueLayerKey(String base) {
        String candidate = base.isEmpty() ? "layer" : base;
        for (int n = 2; exists(candidate); n++) {
            candidate = base + "_" + n;
        }
        return candidate;
    }

    private boolean exists(String key) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM layer_definition WHERE layer_key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    private static String slug(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String str(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static String require(String s, String message) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(message);
        return s;
    }

    private static String oneOf(String s, String what, String... allowed) {
        if (s != null) {
            String up = s.trim().toUpperCase(Locale.ROOT);
            for (String a : allowed) {
                if (a.equals(up)) return a;
            }
        }
        throw new IllegalArgumentException(what + " must be one of " + String.join(", ", allowed));
    }

    /** Thrown when a caller tries to edit or delete a layer its source type protects. */
    public static class ProtectedLayerException extends RuntimeException {
        public ProtectedLayerException(String sourceType) {
            super(switch (sourceType == null ? "" : sourceType) {
                case "SYSTEM_GENERATED" -> "This layer is generated by the system from other data. "
                        + "It cannot be edited or deleted — change the data it is built from instead.";
                case "BUILT_IN" -> "This is a core KLRAMS layer with its own import pipeline. "
                        + "It cannot be edited or deleted from Layer Management.";
                case "EDITABLE_BUILT_IN" -> "This core layer can be renamed but not deleted.";
                default -> "This layer is protected.";
            });
        }
    }

    /** Mutable seed carrier — keeps the seed list readable instead of 15-argument calls. */
    private static final class Layer {
        final String key, folderKey, name;
        String geometry, placement, sourceType, uploadFormats, sourceTable, derivedFrom;
        String sectionField, chainageField, notes, assetType, boundaryType;
        boolean attributeMapping;
        int sort = 100;

        Layer(String key, String folderKey, String name) {
            this.key = key;
            this.folderKey = folderKey;
            this.name = name;
        }
    }
}
