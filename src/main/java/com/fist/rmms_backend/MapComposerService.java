package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The extent arithmetic behind {@link MapComposerController}.
 *
 * <p>Everything here is {@code ST_Extent} — one aggregate per dataset, unioned in Java. Nothing
 * reads a coordinate into the JVM beyond the four numbers each query returns.
 */
@Service
public class MapComposerService {

    /** A physical table name is only ever used after it has come back OUT of layer_definition,
     *  never off the request — but it is still concatenated into SQL, so it is checked against
     *  the shape the registry generates before it goes anywhere near a statement. */
    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** Kerala, generously padded. An extent outside this is a data error (a row with a swapped
     *  lat/lng, a geometry left in a projected CRS), and letting one through would zoom the whole
     *  composed map out to the ocean. Such rows are excluded from the aggregate rather than
     *  rejected, so one bad row cannot cost the map its other 30 000. */
    private static final double MIN_X = 68, MAX_X = 82, MIN_Y = 5, MAX_Y = 16;

    private final JdbcTemplate jdbc;

    public MapComposerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------------------ */
    /* extent                                                             */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    public Map<String, Object> extent(Map<String, Object> req) {
        Map<String, Object> out = new LinkedHashMap<>();
        double[] box = null;
        List<String> problems = new ArrayList<>();
        Map<String, double[]> parts = new LinkedHashMap<>();

        List<String> sections = strings(req.get("sections"));
        boolean wantRoads = truthy(req.get("roads")) || !sections.isEmpty();

        if (wantRoads) {
            box = merge(box, part(parts, "roads", () -> roadsExtent(sections), problems, "road network"));
        }
        if (truthy(req.get("fullNetwork"))) {
            box = merge(box, part(parts, "fullNetwork",
                    () -> tableExtent("full_road_network", null, null), problems, "merged road network"));
        }
        for (String type : strings(req.get("assets"))) {
            String t = type.trim();
            if (t.isEmpty()) continue;
            box = merge(box, part(parts, "asset:" + t,
                    () -> tableExtent("road_assets", "asset_type = ?", new Object[]{t}), problems, "asset " + t));
        }
        for (String b : strings(req.get("boundaries"))) {
            String t = b.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            box = merge(box, part(parts, "boundary:" + t, () -> boundaryExtent(t), problems, "boundary " + t));
        }
        for (Object idRaw : list(req.get("userLayers"))) {
            Integer id = intOf(idRaw);
            if (id == null) continue;
            box = merge(box, part(parts, "layer:" + id, () -> userLayerExtent(id), problems, "layer " + id));
        }

        out.put("ok", true);
        out.put("bbox", box == null ? null : List.of(box[0], box[1], box[2], box[3]));
        Map<String, Object> partsOut = new LinkedHashMap<>();
        parts.forEach((k, v) -> partsOut.put(k, v == null ? null : List.of(v[0], v[1], v[2], v[3])));
        out.put("parts", partsOut);
        if (!problems.isEmpty()) out.put("problems", problems);
        return out;
    }

    /** Runs one extent query, recording its own answer and never letting a failure lose the
     *  others: a composed map with three layers is still worth drawing when one of them has a
     *  broken table. */
    private double[] part(Map<String, double[]> into, String key,
                          ExtentQuery q, List<String> problems, String label) {
        double[] b = null;
        try {
            b = q.run();
        } catch (Exception e) {
            problems.add("Could not read the extent of the " + label + ".");
        }
        into.put(key, b);
        return b;
    }

    private interface ExtentQuery { double[] run(); }

    /** Road network, whole or scoped to a list of Section_La labels.
     *
     *  <p>The labels arrive from the client's own NET_SCOPE — i.e. from the road index it already
     *  holds — and are bound as an array parameter, never interpolated. A scope of zero labels is
     *  a filter that matched nothing, which correctly yields a null extent. */
    private double[] roadsExtent(List<String> sections) {
        if (sections.isEmpty()) return tableExtent("roads", null, null);
        return tableExtent("roads", "\"Section_La\" = ANY (?)",
                new Object[]{sections.toArray(new String[0])});
    }

    private double[] tableExtent(String table, String where, Object[] args) {
        if (!SAFE_TABLE.matcher(table).matches()) return null;
        String sql = "SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM ("
                + " SELECT ST_Extent(t.geom) e FROM " + table + " t"
                + " WHERE t.geom IS NOT NULL AND ST_IsValid(t.geom)"
                // Guard against a stray row in the wrong CRS dragging the page extent off Kerala.
                + " AND ST_XMin(t.geom) >= " + MIN_X + " AND ST_XMax(t.geom) <= " + MAX_X
                + " AND ST_YMin(t.geom) >= " + MIN_Y + " AND ST_YMax(t.geom) <= " + MAX_Y
                + (where == null ? "" : " AND " + where)
                + ") s";
        return queryBox(sql, args);
    }

    /** Boundaries are stored as GeoJSON text (see BoundaryController), not as a geometry column,
     *  so the features are expanded and parsed on the way into ST_Extent. */
    private double[] boundaryExtent(String type) {
        String sql = """
            SELECT ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) FROM (
              SELECT ST_Extent(ST_SetSRID(ST_GeomFromGeoJSON(f->>'geometry'), 4326)) e
              FROM boundary b, jsonb_array_elements(b.geojson::jsonb->'features') f
              WHERE b.type = ? AND f->>'geometry' IS NOT NULL
            ) s
            """;
        return queryBox(sql, new Object[]{type});
    }

    /** A Layer Management / temporary layer, by its layer_definition id. The physical table is
     *  read from the registry — the request only ever supplies an integer id. */
    private double[] userLayerExtent(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT physical_table FROM layer_definition WHERE id = ?", id);
        if (rows.isEmpty()) return null;
        Object t = rows.get(0).get("physical_table");
        if (t == null) return null;
        return tableExtent(String.valueOf(t), null, null);
    }

    private double[] queryBox(String sql, Object[] args) {
        List<Map<String, Object>> rows = (args == null)
                ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Double[] v = new Double[4];
        int i = 0;
        for (Object o : r.values()) {
            if (i > 3) break;
            v[i++] = (o instanceof Number n) ? n.doubleValue() : null;
        }
        for (Double d : v) if (d == null || d.isNaN() || d.isInfinite()) return null;
        return new double[]{v[0], v[1], v[2], v[3]};
    }

    private static double[] merge(double[] a, double[] b) {
        if (b == null) return a;
        if (a == null) return b.clone();
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                            Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /* ------------------------------------------------------------------ */
    /* sources                                                            */
    /* ------------------------------------------------------------------ */

    /** What can be asked for an extent, and whether it currently holds anything. */
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(source("roads", "Road network", count("SELECT count(*) FROM roads WHERE geom IS NOT NULL")));
        out.add(source("fullNetwork", "Merged road network",
                count("SELECT count(*) FROM full_road_network WHERE geom IS NOT NULL")));
        try {
            jdbc.queryForList("SELECT asset_type, count(*) n FROM road_assets"
                            + " WHERE geom IS NOT NULL GROUP BY asset_type ORDER BY asset_type")
                .forEach(r -> out.add(source("asset:" + r.get("asset_type"),
                        String.valueOf(r.get("asset_type")), num(r.get("n")))));
        } catch (Exception ignored) { /* table not built yet */ }
        try {
            jdbc.queryForList("SELECT type FROM boundary ORDER BY type")
                .forEach(r -> out.add(source("boundary:" + r.get("type"),
                        String.valueOf(r.get("type")), -1)));
        } catch (Exception ignored) { /* no boundaries uploaded */ }
        return out;
    }

    private Map<String, Object> source(String key, String label, long features) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("features", features);
        return m;
    }

    private long count(String sql) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /* request coercion                                                   */
    /* ------------------------------------------------------------------ */

    private static long num(Object o) { return (o instanceof Number n) ? n.longValue() : 0; }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static List<?> list(Object o) {
        return (o instanceof List<?> l) ? l : List.of();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object v : list(o)) if (v != null) out.add(String.valueOf(v));
        return out;
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }
}
