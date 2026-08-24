package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loading data into a layer: work out how the file's columns line up with the
 * layer's attributes, then place and store the rows.
 *
 * <h2>Two phases, not one</h2>
 * An upload is deliberately split into {@link #preview} and {@link #importRows}.
 * The existing asset importer does both at once and guesses the mapping from a
 * list of column aliases ({@code section_label}, {@code section_la},
 * {@code label}, {@code road} …), which works right up until someone's file
 * spells it differently — and then silently rejects every row. Here the guess is
 * only a SUGGESTION returned to the user, who confirms or overrides it before a
 * single row is written.
 *
 * <h2>Type mismatches</h2>
 * The rule is the one the Attribute Data module already publishes: a value that
 * does not fit its attribute's type is skipped if the attribute is optional, and
 * fails the row if it is mandatory. Skips are counted and reported rather than
 * hidden, because "1,200 rows loaded" is a lie if 300 of them lost a column.
 *
 * <h2>Temporary layers</h2>
 * A temporary layer is a real layer with {@code temporary = true} and an owner.
 * It behaves exactly like a user layer — same table, same attributes, same
 * import path — so nothing here special-cases it. What differs is only who sees
 * it and that it can be cleared in one action.
 */
@Service
public class LayerDataService {

    private static final Logger log = LoggerFactory.getLogger(LayerDataService.class);

    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Reference length for chainage, the same rule the condition segmentation uses.
     *
     * The column is {@code Measrd_Len} — ten characters, the DBF field-name limit
     * a shapefile import truncates to. The example in CLAUDE.md spelled it
     * {@code Measrd_Ln}, a column that does not exist; copying it from there cost
     * an afternoon, because a failed statement aborts the whole transaction in
     * PostgreSQL and the inserted rows silently vanished at commit.
     */
    private static final String LEN_EXPR = """
            COALESCE(
                NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                NULLIF(r."Measrd_Len"::double precision, 0),
                ST_Length(r.geom::geography))
            """;

    private final JdbcTemplate jdbc;
    private final LayerAttributeService attributes;

    public LayerDataService(JdbcTemplate jdbc, LayerAttributeService attributes) {
        this.jdbc = jdbc;
        this.attributes = attributes;
    }

    /* ------------------------------------------------------------------
       Phase 1 — preview and suggested mapping
       ------------------------------------------------------------------ */

    /**
     * Given the columns found in an uploaded file, propose how they map onto the
     * layer's attributes.
     *
     * Matching is on a normalised form (lowercased, non-alphanumerics stripped),
     * so "Section Label", "section_label" and "SECTION-LABEL" all land on the
     * same attribute without needing an alias table to be maintained by hand.
     */
    public Map<String, Object> preview(int layerId, String dataset, List<String> fileColumns) {
        List<Map<String, Object>> spec = attributes.importSpec(layerId, dataset);

        Map<String, String> byNorm = new LinkedHashMap<>();
        for (String c : fileColumns) {
            if (c != null && !c.isBlank()) byNorm.putIfAbsent(norm(c), c);
        }

        List<Map<String, Object>> mapping = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Map<String, Object> a : spec) {
            String name = String.valueOf(a.get("name"));
            String key = String.valueOf(a.get("storageKey"));
            String match = byNorm.get(norm(name));
            if (match == null) match = byNorm.get(norm(key));
            if (match != null) used.add(match);

            Map<String, Object> m = new LinkedHashMap<>(a);
            m.put("fileColumn", match);          // null = unmapped
            m.put("autoMatched", match != null);
            mapping.add(m);
        }

        List<String> unused = new ArrayList<>();
        for (String c : fileColumns) {
            if (c != null && !c.isBlank() && !used.contains(c)) unused.add(c);
        }

        List<String> missing = new ArrayList<>();
        for (Map<String, Object> m : mapping) {
            if (Boolean.TRUE.equals(m.get("mandatory")) && m.get("fileColumn") == null) {
                missing.add(String.valueOf(m.get("name")));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layerId", layerId);
        out.put("dataset", dataset == null ? "default" : dataset);
        out.put("mapping", mapping);
        out.put("unmappedFileColumns", unused);
        out.put("missingMandatory", missing);
        out.put("ready", missing.isEmpty());
        return out;
    }

    /* ------------------------------------------------------------------
       Phase 2 — import
       ------------------------------------------------------------------ */

    /**
     * Write rows into a layer's table.
     *
     * @param mapping attribute storage key -> file column name
     * @param rows    each row as fileColumn -> raw value
     * @param geoms   optional GeoJSON geometry per row, same order as rows
     */
    @Transactional
    public Map<String, Object> importRows(int layerId, String dataset,
                                          Map<String, String> mapping,
                                          List<Map<String, Object>> rows,
                                          List<String> geoms,
                                          boolean replace) {
        Map<String, Object> layer = jdbc.queryForMap(
                "SELECT id, layer_key, name, physical_table, placement, geometry_type, source_type "
              + "FROM layer_definition WHERE id = ?", layerId);

        String table = String.valueOf(layer.get("physical_table"));
        if (!"USER".equals(String.valueOf(layer.get("source_type"))) || table == null
                || "null".equals(table) || !SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "This layer has its own import pipeline and cannot be loaded from here.");
        }

        String placement = String.valueOf(layer.get("placement"));
        String geometry = String.valueOf(layer.get("geometry_type"));
        List<Map<String, Object>> spec = attributes.importSpec(layerId, dataset);

        for (Map<String, Object> a : spec) {
            if (Boolean.TRUE.equals(a.get("mandatory"))
                    && mapping.get(String.valueOf(a.get("storageKey"))) == null) {
                throw new IllegalArgumentException(
                        "\"" + a.get("name") + "\" is mandatory and has not been mapped to a column.");
            }
        }

        if (replace) jdbc.update("DELETE FROM " + table);

        int loaded = 0, skippedRows = 0, skippedValues = 0;
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> attrs = new LinkedHashMap<>();
            String section = null;
            Double ch = null, chEnd = null;
            boolean rowFailed = false;

            for (Map<String, Object> a : spec) {
                String key = String.valueOf(a.get("storageKey"));
                String col = mapping.get(key);
                if (col == null) continue;

                Object raw = row.get(col);
                String type = String.valueOf(a.get("dataType"));
                boolean required = Boolean.TRUE.equals(a.get("mandatory"));
                Object val = coerce(raw, type);

                if (val == null && raw != null && !String.valueOf(raw).isBlank()) {
                    // The value is present but the wrong shape for its type.
                    if (required) {
                        rowFailed = true;
                        if (problems.size() < 20) {
                            problems.add("Row " + (i + 1) + ": \"" + a.get("name")
                                    + "\" expects " + type.toLowerCase(Locale.ROOT)
                                    + " but found \"" + raw + "\"");
                        }
                        break;
                    }
                    skippedValues++;
                    continue;   // optional -> drop the value, keep the row
                }
                if (val == null) {
                    if (required) {
                        rowFailed = true;
                        if (problems.size() < 20) {
                            problems.add("Row " + (i + 1) + ": \"" + a.get("name") + "\" is required but empty");
                        }
                        break;
                    }
                    continue;
                }

                switch (String.valueOf(a.get("role"))) {
                    case "SECTION_LABEL" -> section = String.valueOf(val);
                    case "CHAINAGE", "START_CHAINAGE" -> ch = toDouble(val);
                    case "END_CHAINAGE" -> chEnd = toDouble(val);
                    default -> { }
                }
                attrs.put(key, val);
            }

            if (rowFailed) { skippedRows++; continue; }

            String geoJson = (geoms != null && i < geoms.size()) ? geoms.get(i) : null;
            try {
                insertRow(table, placement, geometry, attrs, section, ch, chEnd, geoJson);
                loaded++;
            } catch (Exception e) {
                skippedRows++;
                if (problems.size() < 20) problems.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }

        // Chainage placement needs the road network, so it is applied as one set
        // operation after the insert rather than per row.
        int placed = 0;
        if ("LINEAR_REFERENCE".equals(placement)) {
            placed = placeLinearly(table, geometry);
        }

        long unplaced = count("SELECT count(*) FROM " + table + " WHERE geom IS NULL");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "ok");
        r.put("layer", layer.get("name"));
        r.put("loaded", loaded);
        r.put("skippedRows", skippedRows);
        r.put("skippedValues", skippedValues);
        r.put("placed", placed);
        r.put("unplaced", unplaced);
        r.put("problems", problems);
        return r;
    }

    private void insertRow(String table, String placement, String geometry,
                           Map<String, Object> attrs, String section,
                           Double ch, Double chEnd, String geoJson) {
        String attrsJson;
        try {
            attrsJson = JSON.writeValueAsString(attrs);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not encode attributes");
        }

        switch (placement) {
            case "LINEAR_REFERENCE" -> jdbc.update(
                    "INSERT INTO " + table + " (attrs, section_label, start_chainage, end_chainage) "
                  + "VALUES (?::jsonb, ?, ?, ?)", attrsJson, section, ch, chEnd);

            case "LATLNG" -> {
                Double lat = toDouble(attrs.get("lat")), lng = toDouble(attrs.get("lng"));
                if (lat == null || lng == null) {
                    // Fall back to whichever mapped attributes look like coordinates,
                    // so a file using "Latitude"/"Y" still places.
                    lat = firstCoord(attrs, "lat", "latitude", "y");
                    lng = firstCoord(attrs, "lng", "lon", "long", "longitude", "x");
                }
                if (lat == null || lng == null) throw new IllegalArgumentException("missing lat/long");
                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                    throw new IllegalArgumentException("lat/long out of range");
                }
                jdbc.update("INSERT INTO " + table + " (attrs, lat, lng, geom) "
                          + "VALUES (?::jsonb, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))",
                        attrsJson, lat, lng, lng, lat);
            }

            default -> {
                if (geoJson == null || geoJson.isBlank()) {
                    throw new IllegalArgumentException("no geometry in this feature");
                }
                // ST_Multi/ST_GeometryN normalise what the file gives us to the
                // single type the column was declared with, so a Polygon in a
                // MultiPolygon layer (or the reverse) still loads.
                String cast = geometry.startsWith("MULTI")
                        ? "ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))"
                        : "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)";
                jdbc.update("INSERT INTO " + table + " (attrs, geom) VALUES (?::jsonb, " + cast + ")",
                        attrsJson, geoJson);
            }
        }
    }

    /** Place every unplaced row on its road centreline from section + chainage. */
    private int placeLinearly(String table, String geometry) {
        boolean point = "POINT".equals(geometry);
        String sql = point
            ? """
              UPDATE %s a SET geom = ST_LineInterpolatePoint(
                  ST_LineMerge(r.geom),
                  GREATEST(LEAST(a.start_chainage / %s, 1.0), 0.0))
              FROM roads r
              WHERE a.geom IS NULL AND a.section_label = r."Section_La"
                AND a.start_chainage IS NOT NULL AND r.geom IS NOT NULL
              """.formatted(table, LEN_EXPR)
            : """
              UPDATE %s a SET geom = ST_Multi(ST_LineSubstring(
                  ST_LineMerge(r.geom),
                  GREATEST(LEAST(LEAST(a.start_chainage, a.end_chainage) / %s, 1.0), 0.0),
                  GREATEST(LEAST(GREATEST(a.start_chainage, a.end_chainage) / %s, 1.0), 0.0)))
              FROM roads r
              WHERE a.geom IS NULL AND a.section_label = r."Section_La"
                AND a.start_chainage IS NOT NULL AND a.end_chainage IS NOT NULL
                AND a.start_chainage <> a.end_chainage AND r.geom IS NOT NULL
              """.formatted(table, LEN_EXPR, LEN_EXPR);
        /* Deliberately NOT caught.
           This runs inside the import transaction, and PostgreSQL aborts the
           whole transaction on any statement error — so swallowing the exception
           here does not "carry on without placement", it silently rolls back
           every row that was just inserted and still reports success. A bad
           column name in this SQL cost exactly that before it was found. Let it
           surface: the caller turns it into an error the user can act on. */
        return jdbc.update(sql);
    }

    /* ------------------------------------------------------------------
       Reading a layer back for the viewer
       ------------------------------------------------------------------ */

    /** One layer's rows as GeoJSON, for the viewer to draw. */
    public String geojson(int layerId) {
        Map<String, Object> layer = jdbc.queryForMap(
                "SELECT physical_table FROM layer_definition WHERE id = ?", layerId);
        String table = String.valueOf(layer.get("physical_table"));
        if (table == null || "null".equals(table) || !SAFE_TABLE.matcher(table).matches()) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
        String gj = jdbc.queryForObject("""
            SELECT COALESCE(jsonb_build_object(
                       'type','FeatureCollection',
                       'features', COALESCE(jsonb_agg(jsonb_build_object(
                           'type','Feature',
                           'id', t.id,
                           'geometry', ST_AsGeoJSON(t.geom)::jsonb,
                           'properties', COALESCE(t.attrs, '{}'::jsonb)
                       )), '[]'::jsonb))::text,
                       '{"type":"FeatureCollection","features":[]}')
              FROM %s t WHERE t.geom IS NOT NULL
            """.formatted(table), String.class);
        return gj == null ? "{\"type\":\"FeatureCollection\",\"features\":[]}" : gj;
    }

    /** Empty a user layer's rows, keeping the layer and its attributes. */
    @Transactional
    public int clearRows(int layerId) {
        String table = jdbc.queryForObject(
                "SELECT physical_table FROM layer_definition WHERE id = ? AND source_type = 'USER'",
                String.class, layerId);
        if (table == null || !SAFE_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("This layer's data is managed by its own pipeline.");
        }
        return jdbc.update("DELETE FROM " + table);
    }

    /** Layers the viewer should offer: user-created plus this user's temporary ones. */
    public List<Map<String, Object>> viewerLayers(String user) {
        return jdbc.query("""
            SELECT d.id, d.name, d.geometry_type, d.temporary, d.created_by, f.name AS folder
              FROM layer_definition d JOIN layer_folder f ON f.id = d.folder_id
             WHERE d.source_type = 'USER'
               AND (d.temporary IS NOT TRUE OR d.created_by = ?)
             ORDER BY d.temporary NULLS FIRST, f.sort_order, d.name
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("name", rs.getString("name"));
            m.put("geometryType", rs.getString("geometry_type"));
            m.put("temporary", rs.getBoolean("temporary"));
            m.put("folder", rs.getString("folder"));
            return m;
        }, user);
    }

    /* ------------------------------------------------------------------
       Helpers
       ------------------------------------------------------------------ */

    /**
     * Convert a raw file value to its attribute type, or null if it does not fit.
     *
     * Returning null for "present but wrong shape" is what lets the caller apply
     * the mandatory/optional rule — this method deliberately does not decide
     * whether a mismatch is fatal.
     */
    private Object coerce(Object raw, String type) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        return switch (type) {
            case "INTEGER" -> {
                try { yield Long.valueOf(s.replace(",", "")); }
                catch (NumberFormatException e) {
                    // "12.0" is a legitimate integer from a spreadsheet export.
                    try {
                        double d = Double.parseDouble(s.replace(",", ""));
                        yield (d == Math.floor(d)) ? Long.valueOf((long) d) : null;
                    } catch (NumberFormatException e2) { yield null; }
                }
            }
            case "DECIMAL" -> {
                try { yield Double.valueOf(s.replace(",", "")); }
                catch (NumberFormatException e) { yield null; }
            }
            case "BOOLEAN" -> {
                String l = s.toLowerCase(Locale.ROOT);
                if (Set.of("true", "yes", "y", "1").contains(l)) yield Boolean.TRUE;
                if (Set.of("false", "no", "n", "0").contains(l)) yield Boolean.FALSE;
                yield null;
            }
            case "DATE" -> s;   // stored as given; the single accepted format is validated on entry
            default -> s;
        };
    }

    private Double firstCoord(Map<String, Object> attrs, String... keys) {
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String k = norm(e.getKey());
            for (String want : keys) {
                if (k.equals(want)) {
                    Double d = toDouble(e.getValue());
                    if (d != null) return d;
                }
            }
        }
        return null;
    }

    private static Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.valueOf(String.valueOf(o).trim().replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private long count(String sql) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Column names found in a GeoJSON FeatureCollection's properties. */
    public static List<String> columnsOfGeoJson(String geojson) {
        List<String> cols = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(geojson);
            JsonNode feats = root.path("features");
            for (int i = 0; i < Math.min(feats.size(), 50); i++) {
                JsonNode props = feats.get(i).path("properties");
                props.fieldNames().forEachRemaining(n -> {
                    if (!cols.contains(n)) cols.add(n);
                });
            }
        } catch (Exception e) {
            log.warn("Could not read GeoJSON properties: {}", e.toString());
        }
        return cols;
    }
}
