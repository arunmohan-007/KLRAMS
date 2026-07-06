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
    private final ObjectMapper om = new ObjectMapper();

    public TrafficController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_stations (" +
                "name TEXT PRIMARY KEY, road TEXT, section TEXT, chainage DOUBLE PRECISION, " +
                "lat DOUBLE PRECISION, lng DOUBLE PRECISION, xsp TEXT, updated_at TIMESTAMP DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_counts (" +
                "name TEXT PRIMARY KEY, data JSONB, updated_at TIMESTAMP DEFAULT now())");
    }

    /** Add/update stations by name (additive). Body: JSON array of {name,road,section,ch,lat,lng,xsp}.
     *  Existing stations not in this payload are kept; use POST /clear to wipe all. */
    @PostMapping("/stations")
    public Map<String, Object> saveStations(@RequestBody String body) throws Exception {
        JsonNode arr = om.readTree(body);
        int n = 0;
        if (arr != null && arr.isArray()) {
            for (JsonNode s : arr) {
                String name = txt(s, "name");
                if (name == null || name.isEmpty()) continue;
                jdbc.update("INSERT INTO traffic_stations(name,road,section,chainage,lat,lng,xsp,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,now()) ON CONFLICT(name) DO UPDATE SET " +
                                "road=EXCLUDED.road,section=EXCLUDED.section,chainage=EXCLUDED.chainage," +
                                "lat=EXCLUDED.lat,lng=EXCLUDED.lng,xsp=EXCLUDED.xsp,updated_at=now()",
                        name, txt(s, "road"), txt(s, "section"), dbl(s, "ch"),
                        dbl(s, "lat"), dbl(s, "lng"), txt(s, "xsp"));
                n++;
            }
        }
        return Map.of("saved", n);
    }

    /** Add/update counts by station name (additive). Body: JSON object { "<station name>": {...}, ... }.
     *  Existing stations not in this payload are kept; use POST /clear to wipe all. */
    @PostMapping("/counts")
    public Map<String, Object> saveCounts(@RequestBody String body) throws Exception {
        JsonNode obj = om.readTree(body);
        int n = 0;
        if (obj != null && obj.isObject()) {
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                String json = om.writeValueAsString(obj.get(name));
                jdbc.update("INSERT INTO traffic_counts(name,data,updated_at) VALUES(?, ?::jsonb, now()) " +
                        "ON CONFLICT(name) DO UPDATE SET data=EXCLUDED.data, updated_at=now()", name, json);
                n++;
            }
        }
        return Map.of("saved", n);
    }

    /** Full store, in the same shape the viewer/Data Console use. */
    @GetMapping("/store")
    public Map<String, Object> store() {
        List<Map<String, Object>> stations = jdbc.query(
                "SELECT name,road,section,chainage,lat,lng,xsp FROM traffic_stations ORDER BY name",
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
                });
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT name, data::text AS d FROM traffic_counts")) {
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

    /** Stations as a GeoJSON FeatureCollection (lat/lng points). */
    @GetMapping("/stations/geojson")
    public Map<String, Object> stationsGeojson() {
        List<Map<String, Object>> feats = jdbc.query(
                "SELECT name,road,section,chainage,lat,lng,xsp FROM traffic_stations " +
                        "WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY name",
                (rs, i) -> {
                    Map<String, Object> geom = new LinkedHashMap<>();
                    geom.put("type", "Point");
                    geom.put("coordinates", Arrays.asList(rs.getDouble("lng"), rs.getDouble("lat")));
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("name", rs.getString("name"));
                    props.put("road", rs.getString("road"));
                    props.put("section", rs.getString("section"));
                    props.put("ch", rs.getObject("chainage"));
                    props.put("xsp", rs.getString("xsp"));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("type", "Feature");
                    f.put("geometry", geom);
                    f.put("properties", props);
                    return f;
                });
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", feats);
        return fc;
    }

    @PostMapping("/clear")
    public Map<String, Object> clear() {
        jdbc.update("DELETE FROM traffic_stations");
        jdbc.update("DELETE FROM traffic_counts");
        return Map.of("cleared", true);
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
