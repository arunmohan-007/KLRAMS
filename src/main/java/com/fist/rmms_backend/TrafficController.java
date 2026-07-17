package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * KLRAMS — Traffic stations persistence (PostgreSQL).
 *
 * Replaces the browser-localStorage store used by the Data Console and the
 * map viewer. Two tables are created automatically on startup, so no manual
 * SQL is required. Endpoints (all under the existing login / no CSRF):
 *
 *   POST /api/traffic/stations        replace all stations  (JSON array)
 *   POST /api/traffic/counts          replace all counts    (JSON object keyed by station name)
 *   GET  /api/traffic/store           full store {v,savedAt,stations:[...],counts:{...}}
 *   GET  /api/traffic/stations/geojson  stations as a GeoJSON FeatureCollection
 *   POST /api/traffic/clear           delete everything
 */
@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final ObjectMapper om = new ObjectMapper();

    public TrafficController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;   // also orders startup: survey_periods migration runs first
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_stations (" +
                "name TEXT, road TEXT, section TEXT, chainage DOUBLE PRECISION, " +
                "lat DOUBLE PRECISION, lng DOUBLE PRECISION, xsp TEXT, updated_at TIMESTAMP DEFAULT now(), " +
                "period_id INTEGER)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_counts (" +
                "name TEXT, data JSONB, updated_at TIMESTAMP DEFAULT now(), period_id INTEGER)");
        // A station name repeats across survey periods, so identity is
        // (name, period_id). Older databases had name as the primary key —
        // swap it for a surrogate id + a composite unique index (idempotent).
        for (String t : List.of("traffic_stations", "traffic_counts")) {
            jdbc.execute("ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS period_id integer");
            periods.ensureSurrogatePk(t, "name");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + t + "_name_period_ux ON " + t + "(name, period_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS " + t + "_period_idx ON " + t + "(period_id)");
        }
    }

    /** Add/update stations by (name, survey period) — additive. Body: JSON array of
     *  {name,road,section,ch,lat,lng,xsp}. Stations of other periods are never touched.
     *
     *  Placement is by chainage, not lat/lng (see stationsGeojson()), so a row whose
     *  section text doesn't match any roads."Section_La" can never be placed — it is
     *  rejected here rather than imported silently, and named back to the caller so
     *  the bad section label can be fixed at the source. */
    @PostMapping("/stations")
    public Map<String, Object> saveStations(@RequestBody String body,
                                            @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("saved", 0, "error", "Select the survey period this data belongs to before importing.");
        JsonNode arr = om.readTree(body);
        int n = 0;
        if (arr != null && arr.isArray()) {
            for (JsonNode s : arr) {
                String name = txt(s, "name");
                if (name == null || name.isEmpty()) continue;
                jdbc.update("INSERT INTO traffic_stations(name,road,section,chainage,lat,lng,xsp,period_id,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,?,now()) ON CONFLICT(name,period_id) DO UPDATE SET " +
                                "road=EXCLUDED.road,section=EXCLUDED.section,chainage=EXCLUDED.chainage," +
                                "lat=EXCLUDED.lat,lng=EXCLUDED.lng,xsp=EXCLUDED.xsp,updated_at=now()",
                        name, txt(s, "road"), txt(s, "section"), dbl(s, "ch"),
                        dbl(s, "lat"), dbl(s, "lng"), txt(s, "xsp"), periodId);
                n++;
            }
        }

        List<Map<String, Object>> skipped = jdbc.queryForList(
            "SELECT t.name, t.section FROM traffic_stations t " +
            "WHERE t.period_id = ? AND (t.section IS NULL OR t.chainage IS NULL OR NOT EXISTS " +
            "  (SELECT 1 FROM roads r WHERE r.\"Section_La\" = t.section)) ORDER BY t.name", periodId);
        if (!skipped.isEmpty()) {
            jdbc.update(
                "DELETE FROM traffic_stations t WHERE t.period_id = ? AND (t.section IS NULL OR t.chainage IS NULL OR NOT EXISTS " +
                "  (SELECT 1 FROM roads r WHERE r.\"Section_La\" = t.section))", periodId);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("saved", n - skipped.size());
        res.put("skipped", skipped.size());
        res.put("skipped_stations", skipped);
        return res;
    }

    /** Add/update counts by (station name, survey period) — additive. Body: JSON object
     *  { "<station name>": {...}, ... }. Counts of other periods are never touched. */
    @PostMapping("/counts")
    public Map<String, Object> saveCounts(@RequestBody String body,
                                          @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("saved", 0, "error", "Select the survey period this data belongs to before importing.");
        JsonNode obj = om.readTree(body);
        int n = 0;
        if (obj != null && obj.isObject()) {
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                String json = om.writeValueAsString(obj.get(name));
                jdbc.update("INSERT INTO traffic_counts(name,data,period_id,updated_at) VALUES(?, ?::jsonb, ?, now()) " +
                        "ON CONFLICT(name,period_id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()", name, json, periodId);
                n++;
            }
        }
        return Map.of("saved", n);
    }

    /** Full store, in the same shape the viewer/Data Console use.
     *  Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping("/store")
    public Map<String, Object> store(@RequestParam(value = "period_id", required = false) Integer periodId) {
        int pid = periods.resolve(periodId);
        List<Map<String, Object>> stations = jdbc.query(
                "SELECT name,road,section,chainage,lat,lng,xsp FROM traffic_stations WHERE period_id = ? ORDER BY name",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("road", rs.getString("road"));
                    m.put("section", rs.getString("section"));
                    m.put("ch", rs.getObject("chainage"));
                    m.put("lat", rs.getObject("lat"));
                    m.put("lng", rs.getObject("lng"));
                    m.put("xsp", rs.getString("xsp"));
                    return m;
                }, pid);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT name, data::text AS d FROM traffic_counts WHERE period_id = ?", pid)) {
            try {
                // Plain Maps/Lists, NOT JsonNode: Spring Boot 4 serialises responses with
                // Jackson 3, which renders a Jackson-2 JsonNode as its bean properties
                // ({"array":false,"nodeType":"OBJECT",...}) instead of the JSON content.
                counts.put((String) row.get("name"), om.readValue((String) row.get("d"), Object.class));
            } catch (Exception ignore) { }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", 1);
        out.put("savedAt", new Date().toInstant().toString());
        out.put("stations", stations);
        out.put("counts", counts);
        return out;
    }

    /** Stations as a GeoJSON FeatureCollection, placed by linear reference (chainage
     *  along the matching roads."Section_La" centreline) — same method the map viewer
     *  uses client-side, not the station's stored lat/lng. Defaults to the active
     *  survey period; ?period_id= selects another. */
    @GetMapping("/stations/geojson")
    public Map<String, Object> stationsGeojson(@RequestParam(value = "period_id", required = false) Integer periodId) {
        int pid = periods.resolve(periodId);
        String lenExpr = """
            COALESCE(
                NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                NULLIF(r."Measrd_Len"::double precision, 0),
                ST_Length(r.geom::geography))
            """;
        List<Map<String, Object>> feats = jdbc.query("""
                SELECT t.name, t.road, t.section, t.chainage, t.lat, t.lng, t.xsp,
                       ST_X(ST_LineInterpolatePoint(ST_LineMerge(r.geom), GREATEST(LEAST(t.chainage / %1$s, 1.0), 0.0))) AS px,
                       ST_Y(ST_LineInterpolatePoint(ST_LineMerge(r.geom), GREATEST(LEAST(t.chainage / %1$s, 1.0), 0.0))) AS py
                FROM traffic_stations t JOIN roads r ON r."Section_La" = t.section
                WHERE t.chainage IS NOT NULL AND r.geom IS NOT NULL AND t.period_id = ?
                ORDER BY t.name
                """.formatted(lenExpr),
                (rs, i) -> {
                    Map<String, Object> geom = new LinkedHashMap<>();
                    geom.put("type", "Point");
                    geom.put("coordinates", Arrays.asList(rs.getDouble("px"), rs.getDouble("py")));
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("name", rs.getString("name"));
                    props.put("road", rs.getString("road"));
                    props.put("section", rs.getString("section"));
                    props.put("ch", rs.getObject("chainage"));
                    props.put("lat", rs.getObject("lat"));
                    props.put("lng", rs.getObject("lng"));
                    props.put("xsp", rs.getString("xsp"));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("type", "Feature");
                    f.put("geometry", geom);
                    f.put("properties", props);
                    return f;
                }, pid);
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", feats);
        return fc;
    }

    /** Clears ONE survey period's stations and counts (other periods are kept). */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestParam(value = "periodId", required = false) Integer periodId) {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("cleared", false, "error", "Select the survey period to clear.");
        int a = jdbc.update("DELETE FROM traffic_stations WHERE period_id = ?", periodId);
        int b = jdbc.update("DELETE FROM traffic_counts WHERE period_id = ?", periodId);
        return Map.of("cleared", true, "stations", a, "counts", b);
    }

    private String txt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private Double dbl(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().trim().isEmpty())) return null;
        try { return v.asDouble(); } catch (Exception e) { return null; }
    }
}
