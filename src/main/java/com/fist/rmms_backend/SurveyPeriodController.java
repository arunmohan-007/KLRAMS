package com.fist.rmms_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Survey Periods admin API (Data Console + Survey Archive).
 *
 *   GET    /api/survey-periods               list, newest first
 *   POST   /api/survey-periods               create {name, start_date, end_date} (YYYY-MM-DD)
 *   PUT    /api/survey-periods/{id}          rename / change dates
 *   POST   /api/survey-periods/{id}/activate make this the period the main map shows
 *   DELETE /api/survey-periods/{id}          only when empty and not active
 *   GET    /api/survey-periods/availability  per-period row counts for every survey dataset
 */
@RestController
@RequestMapping("/api/survey-periods")
public class SurveyPeriodController {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    public SurveyPeriodController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return periods.list();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String err = validate(body, null);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("status", "error", "message", err));
        jdbc.update("INSERT INTO survey_periods(name, start_date, end_date) VALUES (?, ?::date, ?::date)",
                body.get("name").trim(), body.get("start_date"), body.get("end_date"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable int id, @RequestBody Map<String, String> body) {
        if (!periods.exists(id))
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Unknown period"));
        String err = validate(body, id);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("status", "error", "message", err));
        jdbc.update("UPDATE survey_periods SET name = ?, start_date = ?::date, end_date = ?::date WHERE id = ?",
                body.get("name").trim(), body.get("start_date"), body.get("end_date"), id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable int id) {
        if (!periods.exists(id))
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Unknown period"));
        periods.activate(id);
        return ResponseEntity.ok(Map.of("status", "ok", "active", id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable int id) {
        if (!periods.exists(id))
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Unknown period"));
        if (id == periods.activePeriodId())
            return ResponseEntity.status(409).body(Map.of("status", "error",
                    "message", "This period is active — activate another period first."));
        long rows = totalRows(id);
        if (rows > 0)
            return ResponseEntity.status(409).body(Map.of("status", "error",
                    "message", "Period still holds " + rows + " data row(s) — it can only be deleted when empty."));
        jdbc.update("DELETE FROM survey_periods WHERE id = ?", id);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Row counts per period per dataset — drives the console table and the
     *  Survey Archive availability toggles. */
    @GetMapping("/availability")
    public Map<String, Object> availability() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : periods.list()) {
            int id = ((Number) p.get("id")).intValue();
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("condition", count("SELECT count(*) FROM condition WHERE period_id = ?", id, "condition"));
            counts.put("condition_segments", count("SELECT count(*) FROM condition_segments WHERE period_id = ?", id, "condition_segments"));
            counts.put("fwd", assetCount("fwd", id));
            counts.put("fwd_segments", count("SELECT count(*) FROM fwd_segments WHERE period_id = ?", id, "fwd_segments"));
            counts.put("subgrade", assetCount("subgrade", id));
            counts.put("bituminous_core", assetCount("bituminous_core", id));
            counts.put("pavement_crust", assetCount("pavement_crust", id));
            counts.put("traffic_stations", count("SELECT count(*) FROM traffic_stations WHERE period_id = ?", id, "traffic_stations"));
            counts.put("traffic_counts", count("SELECT count(*) FROM traffic_counts WHERE period_id = ?", id, "traffic_counts"));
            counts.put("videos", count("SELECT count(*) FROM road_video WHERE period_id = ?", id, "road_video"));
            Map<String, Object> row = new LinkedHashMap<>(p);
            row.put("counts", counts);
            out.add(row);
        }
        return Map.of("periods", out);
    }

    private long totalRows(int id) {
        long n = 0;
        n += count("SELECT count(*) FROM condition WHERE period_id = ?", id, "condition");
        n += count("SELECT count(*) FROM road_assets WHERE period_id = ?", id, "road_assets");
        n += count("SELECT count(*) FROM traffic_stations WHERE period_id = ?", id, "traffic_stations");
        n += count("SELECT count(*) FROM traffic_counts WHERE period_id = ?", id, "traffic_counts");
        n += count("SELECT count(*) FROM road_video WHERE period_id = ?", id, "road_video");
        return n;
    }

    private long count(String sql, int id, String table) {
        if (!periods.tableExists(table)) return 0;
        Long n = jdbc.queryForObject(sql, Long.class, id);
        return n == null ? 0 : n;
    }

    private long assetCount(String type, int id) {
        if (!periods.tableExists("road_assets")) return 0;
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM road_assets WHERE asset_type = ? AND period_id = ?", Long.class, type, id);
        return n == null ? 0 : n;
    }

    private String validate(Map<String, String> body, Integer selfId) {
        String name = body.get("name");
        if (name == null || name.trim().isEmpty()) return "Period name is required.";
        String s = body.get("start_date"), e = body.get("end_date");
        LocalDate sd, ed;
        try { sd = LocalDate.parse(s); ed = LocalDate.parse(e); }
        catch (Exception ex) { return "Start and end dates are required (YYYY-MM-DD)."; }
        if (ed.isBefore(sd)) return "End date must be on or after the start date.";
        Long dup = selfId == null
                ? jdbc.queryForObject("SELECT count(*) FROM survey_periods WHERE lower(name) = lower(?)", Long.class, name.trim())
                : jdbc.queryForObject("SELECT count(*) FROM survey_periods WHERE lower(name) = lower(?) AND id <> ?", Long.class, name.trim(), selfId);
        if (dup != null && dup > 0) return "A period named \"" + name.trim() + "\" already exists.";
        return null;
    }
}
