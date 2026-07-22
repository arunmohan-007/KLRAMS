package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores and serves boundary layers (district, constituency, ...) as GeoJSON.
 * The shapefile zip is parsed in the browser; this only keeps the resulting
 * GeoJSON text in a small table, keyed by boundary type.
 */
@RestController
@RequestMapping("/api/boundary")
public class BoundaryController {

    private final JdbcTemplate jdbc;

    public BoundaryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensure() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS boundary (type text PRIMARY KEY, geojson text)");
    }

    @PostMapping("/{type}")
    public Map<String, Object> save(@PathVariable String type, @RequestBody String geojson) {
        Map<String, Object> r = new HashMap<>();
        try {
            ensure();
            jdbc.update("""
                INSERT INTO boundary (type, geojson) VALUES (?, ?)
                ON CONFLICT (type) DO UPDATE SET geojson = EXCLUDED.geojson
                """, type, geojson);
            r.put("status", "ok");
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("boundary save", e));
        }
        return r;
    }

    /** Remove the stored boundary for a type (district / constituency). */
    @DeleteMapping("/{type}")
    public Map<String, Object> remove(@PathVariable String type) {
        Map<String, Object> r = new HashMap<>();
        try {
            ensure();
            int n = jdbc.update("DELETE FROM boundary WHERE type = ?", type);
            r.put("status", "ok");
            r.put("removed", n);
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("boundary delete", e));
        }
        return r;
    }

    @GetMapping(value = "/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String get(@PathVariable String type) {
        try {
            String g = jdbc.queryForObject("SELECT geojson FROM boundary WHERE type = ?", String.class, type);
            return g != null ? g : emptyFC();
        } catch (Exception e) {
            return emptyFC();
        }
    }

    private String emptyFC() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
