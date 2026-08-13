package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stores and serves boundary layers (district, constituency) as GeoJSON.
 *
 * <p>The shapefile zip is parsed in the browser; this keeps the resulting GeoJSON
 * text in {@code boundary(type, geojson)}.
 */
@RestController
@RequestMapping("/api/boundary")
public class BoundaryController {

    private static final Pattern SAFE_TYPE = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");
    private static final Set<String> KNOWN = Set.of("district", "constituency");

    private final JdbcTemplate jdbc;

    public BoundaryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensure() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS boundary (type text PRIMARY KEY, geojson text)");
    }

    private boolean knownType(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(t) || SAFE_TYPE.matcher(t).matches();
    }

    private boolean hasData(String type) {
        try {
            String g = jdbc.queryForObject(
                    "SELECT geojson FROM boundary WHERE type = ?", String.class, type);
            return g != null && g.contains("\"features\"") && !g.contains("\"features\":[]");
        } catch (Exception e) {
            return false;
        }
    }

    private long featureCount(String type) {
        try {
            String g = jdbc.queryForObject(
                    "SELECT geojson FROM boundary WHERE type = ?", String.class, type);
            if (g == null) return 0;
            int n = 0, idx = 0;
            while ((idx = g.indexOf("\"type\":\"Feature\"", idx)) >= 0) {
                n++;
                idx += 16;
            }
            if (n == 0) {
                idx = 0;
                while ((idx = g.indexOf("\"type\": \"Feature\"", idx)) >= 0) {
                    n++;
                    idx += 17;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    @GetMapping("/{type}/status")
    public Map<String, Object> status(@PathVariable String type) {
        Map<String, Object> r = new HashMap<>();
        r.put("type", type);
        boolean has = knownType(type) && hasData(type);
        r.put("hasData", has);
        r.put("featureCount", knownType(type) ? featureCount(type) : 0);
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
            if (!knownType(type)) {
                r.put("status", "error");
                r.put("message", "Unknown boundary layer '" + type + "'.");
                return r;
            }
            String key = type.trim().toLowerCase(Locale.ROOT);
            ensure();
            boolean has = hasData(key);
            if (has && "replace".equalsIgnoreCase(mode) && !force) {
                r.put("status", "exists");
                r.put("existing", true);
                r.put("featureCount", featureCount(key));
                r.put("message", "Boundary data already exists. Re-post with force=true to replace, or mode=add to merge features.");
                return r;
            }
            String toStore = geojson;
            if (has && "add".equalsIgnoreCase(mode)) {
                toStore = mergeGeoJson(key, geojson);
            }
            jdbc.update("""
                INSERT INTO boundary (type, geojson) VALUES (?, ?)
                ON CONFLICT (type) DO UPDATE SET geojson = EXCLUDED.geojson
                """, key, toStore);
            r.put("status", "ok");
            r.put("mode", mode);
            r.put("featureCount", featureCount(key));
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("boundary save", e));
        }
        return r;
    }

    /** Remove the stored boundary GeoJSON for a type. */
    @DeleteMapping("/{type}")
    public Map<String, Object> remove(@PathVariable String type) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (!knownType(type)) {
                r.put("status", "error");
                r.put("message", "Unknown boundary layer.");
                return r;
            }
            ensure();
            int n = jdbc.update("DELETE FROM boundary WHERE type = ?", type.trim().toLowerCase(Locale.ROOT));
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
