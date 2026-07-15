package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Survey Dashboard figures — per survey period (see {@link SurveyPeriodService})
 * and district-wise volumes for every field survey stream:
 *
 *   nsv_lane_km      — NSV road-condition survey, lane-km = Σ(end−start chainage)
 *                      over the raw per-lane condition rows (each row is one XSP
 *                      lane strip, so the sum is lane-km, not centreline km)
 *   fwd_points       — FWD test points  (road_assets, asset_type='fwd')
 *   traffic_stations — traffic count stations (traffic_stations table)
 *   subgrade_tests   — sub-grade soil tests   (road_assets, asset_type='subgrade')
 *   bituminous_cores — bituminous core cuts   (road_assets, asset_type='bituminous_core')
 *
 * Every stream is tagged with a survey period at import time (period_id column),
 * so the dashboard groups on that directly — no more guessing the cycle from
 * date attributes.
 *
 * District is resolved by joining the record's section label to
 * roads."Section_La" (unique per section) and reading roads."District".
 */
@RestController
@RequestMapping("/api/survey-dashboard")
public class SurveyDashboardController {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    public SurveyDashboardController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    private static final String DIST =
        "COALESCE(NULLIF(trim(r.\"District\"),''),'(unmapped)')";

    /* (district, period_id, n) for one point-type survey stored in road_assets */
    private List<Map<String, Object>> assetCounts(String assetType) {
        return jdbc.queryForList(
            "SELECT " + DIST + " AS district, a.period_id AS pid, COUNT(*) AS n " +
            "FROM road_assets a LEFT JOIN roads r ON r.\"Section_La\" = a.section_label " +
            "WHERE a.asset_type = ? AND a.period_id IS NOT NULL GROUP BY 1, 2", assetType);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {

        List<Map<String, Object>> fwd  = assetCounts("fwd");
        List<Map<String, Object>> soil = assetCounts("subgrade");
        List<Map<String, Object>> core = assetCounts("bituminous_core");

        List<Map<String, Object>> nsv = jdbc.queryForList(
            "SELECT " + DIST + " AS district, c.period_id AS pid, " +
            "       ROUND(SUM(GREATEST(c.end_chainage - c.start_chainage, 0))::numeric/1000, 1) AS lane_km " +
            "FROM condition c LEFT JOIN roads r ON r.\"Section_La\" = c.section_label " +
            "WHERE c.period_id IS NOT NULL GROUP BY 1, 2");

        /* A dual-carriageway station is stored as two rows whose names differ only
           by a trailing A/B after the station number (TVM_STN_021A / TVM_STN_021B):
           count the pair once by grouping on the name with that suffix stripped. */
        List<Map<String, Object>> traffic;
        try {
            traffic = jdbc.queryForList(
                "SELECT district, pid, COUNT(DISTINCT base_name) AS n FROM (" +
                "  SELECT " + DIST + " AS district, t.period_id AS pid, " +
                "         regexp_replace(trim(t.name), '([0-9])[ABab]$', '\\1') AS base_name " +
                "  FROM traffic_stations t LEFT JOIN roads r ON r.\"Section_La\" = t.section" +
                "  WHERE t.period_id IS NOT NULL" +
                ") x GROUP BY 1, 2");
        } catch (Exception e) {
            traffic = Collections.emptyList();
        }

        /* ---- merge: period -> district -> metric map ---- */
        Map<Integer, Map<String, Map<String, Object>>> byPeriod = new HashMap<>();

        add(byPeriod, fwd,  "fwd_points",       "n");
        add(byPeriod, soil, "subgrade_tests",   "n");
        add(byPeriod, core, "bituminous_cores", "n");
        add(byPeriod, traffic, "traffic_stations", "n");
        add(byPeriod, nsv,  "nsv_lane_km",      "lane_km");

        int activeId = periods.activePeriodId();

        /* Emit in the period list's order (newest first) so the dashboard pills
           read naturally; include every period even when it has no data yet. */
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> defaultPeriod = null;
        for (Map<String, Object> p : periods.list()) {
            int pid = ((Number) p.get("id")).intValue();
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("id", pid);
            period.put("name", p.get("name"));
            period.put("range", rangeLabel(p));
            period.put("is_active", Boolean.TRUE.equals(p.get("is_active")));

            Map<String, Object> totals = blankMetrics();
            List<Map<String, Object>> dists = new ArrayList<>();
            Map<String, Map<String, Object>> districts =
                byPeriod.getOrDefault(pid, Collections.emptyMap());
            districts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(de -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("district", de.getKey());
                    for (String k : METRICS) {
                        double v = ((Number) de.getValue().getOrDefault(k, 0d)).doubleValue();
                        double t = ((Number) totals.get(k)).doubleValue() + v;
                        // no ternary here: mixing Double/Long branches would box both to Double
                        if (k.equals("nsv_lane_km")) {
                            d.put(k, Math.round(v * 10) / 10.0);
                            totals.put(k, Math.round(t * 10) / 10.0);
                        } else {
                            d.put(k, (long) v);
                            totals.put(k, (long) t);
                        }
                    }
                    dists.add(d);
                });
            period.put("totals", totals);
            period.put("districts", dists);
            out.add(period);
            if (pid == activeId) defaultPeriod = Map.of("id", pid, "name", p.get("name"));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("default_period", defaultPeriod);
        res.put("periods", out);
        return res;
    }

    private static String rangeLabel(Map<String, Object> p) {
        Object s = p.get("start_date"), e = p.get("end_date");
        if (s == null && e == null) return "";
        return (s == null ? "…" : s) + " – " + (e == null ? "…" : e);
    }

    private static final List<String> METRICS = List.of(
        "nsv_lane_km", "fwd_points", "traffic_stations", "subgrade_tests", "bituminous_cores");

    private static Map<String, Object> blankMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : METRICS) m.put(k, k.equals("nsv_lane_km") ? 0.0 : 0L);
        return m;
    }

    private static void add(Map<Integer, Map<String, Map<String, Object>>> byPeriod,
                            List<Map<String, Object>> rows, String metric, String valueCol) {
        for (Map<String, Object> row : rows) {
            Number pid = (Number) row.get("pid");
            if (pid == null) continue;
            String district = (String) row.get("district");
            double v = ((Number) row.get(valueCol)).doubleValue();
            byPeriod.computeIfAbsent(pid.intValue(), k -> new TreeMap<>())
                    .computeIfAbsent(district == null ? "(unmapped)" : district, k -> new LinkedHashMap<>())
                    .merge(metric, v, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
        }
    }
}
