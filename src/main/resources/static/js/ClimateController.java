package com.fist.rmms_backend.climate;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Climate module API (build 76). All endpoints require an authenticated session
 * (covered by SecurityConfig's "everything else authenticated" rule — no change there).
 *
 * GET is allowed on /seed-grid and /recalc purely for easy browser testing now;
 * the Climate Data Console (build 79) will call them as POST.
 */
@RestController
@RequestMapping("/api/climate")
public class ClimateController {

    private final ClimateService svc;

    public ClimateController(ClimateService svc) {
        this.svc = svc;
    }

    /** All editable config (layers, classifications, cvi, cvi_bands). */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return svc.getAllConfig();
    }

    @GetMapping("/config/{key}")
    public ResponseEntity<Object> config(@PathVariable String key) {
        Object v = svc.getConfig(key);
        return v == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(v);
    }

    /** Save one config key. Body = JSON (array or object depending on key). */
    @PutMapping("/config/{key}")
    public ResponseEntity<Map<String, Object>> saveConfig(@PathVariable String key,
                                                          @RequestBody String body) {
        try {
            svc.putConfig(key, body);
            return ResponseEntity.ok(ok("saved", Map.of("key", key)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        }
    }

    /** Materialise the per-chainage grid from condition_segments. */
    @RequestMapping(value = "/seed-grid", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> seedGrid() {
        try {
            int n = svc.seedGrid();
            return ResponseEntity.ok(ok("grid seeded", Map.of("inserted", n)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(err(
                "seed-grid failed — check condition_segments column names. " + e.getMessage()));
        }
    }

    /** Re-derive idx_* -> cvi -> category from stored raw values + current config. */
    @RequestMapping(value = "/recalc", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> recalc() {
        try {
            int n = svc.recalc();
            return ResponseEntity.ok(ok("recalculated", Map.of("segments", n)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(err(e.getMessage()));
        }
    }

    /** Raw segment rows (verification; viewer geojson comes in build 78). */
    @GetMapping("/segments")
    public Object segments() {
        return svc.allSegments();
    }

    /** Count by CVI category. */
    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return svc.categorySummary();
    }

    // ---- small response helpers ----
    private Map<String, Object> ok(String msg, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("message", msg);
        if (extra != null) m.putAll(extra);
        return m;
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", msg);
        return m;
    }
}
