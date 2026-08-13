package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores and serves boundary layers (district, constituency, and any names created
 * under Layer Management → Administrative Boundary) as GeoJSON.
 *
 * <p>The shapefile zip is parsed in the browser; this keeps the resulting GeoJSON
 * text in {@code boundary(type, geojson)}. Layer names must exist in
 * {@code admin_boundary_layers} (see {@link ManagedLayerService}).
 */
@RestController
@RequestMapping("/api/boundary")
public class BoundaryController {

    private final JdbcTemplate jdbc;
    private final ManagedLayerService managed;

    public BoundaryController(JdbcTemplate jdbc, ManagedLayerService managed) {
        this.jdbc = jdbc;
        this.managed = managed;
    }

    private void ensure() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS boundary (type text PRIMARY KEY, geojson text)");
    }

    @GetMapping("/{type}/status")
    public Map<String, Object> status(@PathVariable String type) {
        Map<String, Object> r = new HashMap<>();
        r.put("type", type);
        r.put("managed", managed.isManagedBoundary(type));
        boolean has = managed.boundaryHasData(type);
        r.put("hasData", has);
        r.put("featureCount", managed.boundaryFeatureCount(type));
        r.put("message", has
                ? "Data already uploaded for this boundary. Replace the whole layer, or cancel."
                : "No data yet — ready for first upload.");
        return r;
    }

    /**
     * @param mode {@code replace} (default) overwrites; {@code add} merges FeatureCollections.
     * @param force when false and data exists in replace mode, returns status=exists for confirm.
     */
    @PostMapping("/{type}")
    public Map<String, Object> save(@PathVariable String type,
                                    @RequestBody String geojson,
                                    @RequestParam(value = "mode", defaultValue = "replace") String mode,
                                    @RequestParam(value = "force", defaultValue = "false") boolean force) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (!managed.isManagedBoundary(type)) {
                r.put("status", "error");
                r.put("message", "Unknown boundary layer '" + type
                        + "'. Create it first under Layer Management → Administrative Boundary.");
                return r;
            }
            ensure();
            boolean has = managed.boundaryHasData(type);
            if (has && "replace".equalsIgnoreCase(mode) && !force) {
                r.put("status", "exists");
                r.put("existing", true);
                r.put("featureCount", managed.boundaryFeatureCount(type));
                r.put("message", "Boundary data already exists. Re-post with force=true to replace, or mode=add to merge features.");
                return r;
            }
            String toStore = geojson;
            if (has && "add".equalsIgnoreCase(mode)) {
                toStore = mergeGeoJson(type, geojson);
            }
            jdbc.update("""
                INSERT INTO boundary (type, geojson) VALUES (?, ?)
                ON CONFLICT (type) DO UPDATE SET geojson = EXCLUDED.geojson
                """, type, toStore);
            r.put("status", "ok");
            r.put("mode", mode);
            r.put("featureCount", managed.boundaryFeatureCount(type));
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("boundary save", e));
        }
        return r;
    }

    /** Remove the stored boundary GeoJSON for a type (registry row stays). */
    @DeleteMapping("/{type}")
    public Map<String, Object> remove(@PathVariable String type) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (!managed.isManagedBoundary(type)) {
                r.put("status", "error");
                r.put("message", "Unknown boundary layer.");
                return r;
            }
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

    private String mergeGeoJson(String type, String incoming) {
        String existing;
        try {
            existing = jdbc.queryForObject(
                    "SELECT geojson FROM boundary WHERE type = ?", String.class, type);
        } catch (Exception e) {
            return incoming;
        }
        if (existing == null || existing.isBlank()) return incoming;
        // Minimal merge: concatenate feature arrays without a full JSON parser dependency path.
        int a = existing.lastIndexOf(']');
        int b = incoming.indexOf('[');
        int c = incoming.lastIndexOf(']');
        if (a < 0 || b < 0 || c < 0 || c <= b) return incoming;
        String left = existing.substring(0, a).trim();
        String right = incoming.substring(b + 1, c).trim();
        if (left.endsWith("[")) {
            return left + right + "]}";
        }
        if (right.isEmpty()) return existing;
        return left + "," + right + "]}";
    }

    private String emptyFC() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
