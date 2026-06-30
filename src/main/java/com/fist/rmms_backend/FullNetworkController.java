package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * "Full Road Network (by Road Name)" — the SECOND road-network layer.
 *
 * Mirrors the section-based road layer but stores its data permanently in its
 * own table {@code full_road_network}, so imports survive page refresh AND
 * server restart.
 *
 *  - The shapefile is parsed in the browser (shpjs) and POSTed here as GeoJSON.
 *  - Each feature is UPSERTED by road number (falling back to road name):
 *      same key  -> UPDATE the existing road
 *      new key   -> INSERT it
 *    (nothing else is wiped, so you can import in batches).
 *  - Properties are stored as jsonb, so shapefiles with different column sets
 *    all work without schema changes.
 *  - The assembled GeoJSON is cached in memory and rebuilt after each upload.
 *
 * Endpoints:
 *    GET  /api/full-network/geojson   -> FeatureCollection (cached)
 *    POST /api/full-network/upload    -> upsert GeoJSON (mode=merge default, or replace)
 */
@RestController
@RequestMapping("/api/full-network")
public class FullNetworkController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();
    private volatile String cachedGeojson;

    // upsert key: try Road_id first (the dataset's unique id), then road number, then name
    private static final String[] KEY_FIELDS = {"Road_id","Road_ID","ROAD_ID","RoadId","road_id","ROAD_id"};
    private static final String[] NUM_KEYS  = {"Road_Num","Road_No","ROAD_NO","RoadNo","road_no","Road_Number","ROAD_NUM"};
    private static final String[] NAME_KEYS = {"Road_Name","ROAD_NAME","RoadName","road_name","Name","NAME","name"};

    public FullNetworkController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Create the table + spatial index on startup if they don't already exist. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS full_road_network (
                id        serial PRIMARY KEY,
                road_key  text UNIQUE NOT NULL,
                road_name text,
                road_num  text,
                props     jsonb,
                geom      geometry(MultiLineString,4326)
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS full_road_network_geom_idx ON full_road_network USING GIST (geom)");
    }

    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson() {
        String body = cachedGeojson;
        if (body == null) {
            synchronized (this) {
                if (cachedGeojson == null) cachedGeojson = build();
                body = cachedGeojson;
            }
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String build() {
        return jdbc.queryForObject("""
            SELECT json_build_object(
                'type','FeatureCollection',
                'features', COALESCE(json_agg(
                    json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(f.geom)::json,
                        'properties', f.props
                    )
                ), '[]'::json)
            )::text
            FROM full_road_network f
            WHERE f.geom IS NOT NULL
            """, String.class);
    }

    @PostMapping("/upload")
    @Transactional
    public Map<String, Object> upload(@RequestParam(defaultValue = "merge") String mode,
                                      @RequestBody String body) {
        Map<String, Object> r = new HashMap<>();
        try {
            JsonNode gj = om.readTree(body);
            JsonNode feats = gj.get("features");
            if (feats == null || !feats.isArray() || feats.size() == 0)
                return err(r, "No features found in the uploaded file.");

            // validate geometry types up front (abort before any change)
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < feats.size(); i++) {
                JsonNode g = feats.get(i).get("geometry");
                String gt = g != null && g.hasNonNull("type") ? g.get("type").asText() : "";
                if (!gt.equals("LineString") && !gt.equals("MultiLineString"))
                    problems.add("feature " + (i + 1) + ": geometry is " + (gt.isEmpty() ? "missing" : gt) + ", expected LineString");
                if (problems.size() >= 8) break;
            }
            if (!problems.isEmpty())
                return err(r, "Validation failed — nothing was changed. " + String.join("; ", problems));

            int removed = 0;
            if (mode.equalsIgnoreCase("replace"))
                removed = jdbc.update("DELETE FROM full_road_network");

            int inserted = 0, updated = 0;
            for (JsonNode f : feats) {
                JsonNode p = f.get("properties");
                if (p == null) p = om.createObjectNode();
                String geomJson  = om.writeValueAsString(f.get("geometry"));
                String propsJson = om.writeValueAsString(p);
                String rid  = firstNonEmpty(p, KEY_FIELDS);
                String num  = firstNonEmpty(p, NUM_KEYS);
                String name = firstNonEmpty(p, NAME_KEYS);
                String key  = !rid.isEmpty() ? rid
                            : (!num.isEmpty() ? num
                            : (!name.isEmpty() ? name : "auto-" + UUID.randomUUID()));

                boolean exists = false;
                if (!mode.equalsIgnoreCase("replace")) {
                    Integer c = jdbc.queryForObject(
                        "SELECT count(*) FROM full_road_network WHERE road_key = ?", Integer.class, key);
                    exists = c != null && c > 0;
                }

                jdbc.update("""
                    INSERT INTO full_road_network (road_key, road_name, road_num, props, geom)
                    VALUES (?,?,?,?::jsonb, ST_SetSRID(ST_Multi(ST_GeomFromGeoJSON(?)),4326))
                    ON CONFLICT (road_key) DO UPDATE SET
                        road_name = EXCLUDED.road_name,
                        road_num  = EXCLUDED.road_num,
                        props     = EXCLUDED.props,
                        geom      = EXCLUDED.geom
                    """,
                    key,
                    name.isEmpty() ? null : name,
                    num.isEmpty()  ? null : num,
                    propsJson,
                    geomJson);

                if (exists) updated++; else inserted++;
            }

            synchronized (this) { cachedGeojson = null; } // rebuild on next read

            Long total = jdbc.queryForObject("SELECT count(*) FROM full_road_network", Long.class);
            r.put("status", "ok");
            r.put("mode", mode);
            r.put("inserted", inserted);
            r.put("updated", updated);
            if (mode.equalsIgnoreCase("replace")) r.put("removed_old", removed);
            r.put("total", total);
            return r;
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    private static String firstNonEmpty(JsonNode p, String[] keys) {
        for (String k : keys) {
            if (p.hasNonNull(k)) {
                String v = p.get(k).asText().trim();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private Map<String, Object> err(Map<String, Object> r, String m) {
        r.put("status", "error");
        r.put("message", m);
        return r;
    }
}
