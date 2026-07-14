package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Survey Dashboard figures — year-wise (financial year, April–March) and
 * district-wise volumes for every field survey stream:
 *
 *   nsv_lane_km      — NSV road-condition survey, lane-km = Σ(end−start chainage)
 *                      over the raw per-lane condition rows (each row is one XSP
 *                      lane strip, so the sum is lane-km, not centreline km)
 *   fwd_points       — FWD test points  (road_assets, asset_type='fwd')
 *   traffic_stations — traffic count stations (traffic_stations table)
 *   subgrade_tests   — sub-grade soil tests   (road_assets, asset_type='subgrade')
 *   bituminous_cores — bituminous core cuts   (road_assets, asset_type='bituminous_core')
 *
 * Survey year comes from the survey-date attribute where the upload carries one
 * ('Survey Start Date' for FWD, 'Date' for soil/core, format DD-Mon-YYYY);
 * a date in April or later belongs to that year's cycle (e.g. Dec-2025 → 2025-26).
 * Streams with no per-row date (condition rows, traffic stations) are attributed
 * to the modal (most common) survey year of the dated streams, falling back to
 * the current financial year when nothing dated is loaded yet.
 *
 * District is resolved by joining the record's section label to
 * roads."Section_La" (unique per section) and reading roads."District".
 */
@RestController
@RequestMapping("/api/survey-dashboard")
public class SurveyDashboardController {

    private final JdbcTemplate jdbc;

    public SurveyDashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* fy_start = calendar year the financial year begins in (2025 → "2025-26") */
    private static String fyLabel(int fyStart) {
        return fyStart + "-" + String.format("%02d", (fyStart + 1) % 100);
    }

    private static final String DIST =
        "COALESCE(NULLIF(trim(r.\"District\"),''),'(unmapped)')";

    /* CASE expression turning a DD-Mon-YYYY attr into the financial-year start */
    private static String fyExpr(String dateAttr) {
        return "(CASE WHEN (a.attrs->>'" + dateAttr + "') ~ '^[0-9]{1,2}-[A-Za-z]{3}-[0-9]{4}$' THEN " +
               " (CASE WHEN extract(month FROM to_date(a.attrs->>'" + dateAttr + "','DD-Mon-YYYY'))>=4 " +
               "       THEN extract(year FROM to_date(a.attrs->>'" + dateAttr + "','DD-Mon-YYYY')) " +
               "       ELSE extract(year FROM to_date(a.attrs->>'" + dateAttr + "','DD-Mon-YYYY'))-1 END)::int " +
               "ELSE NULL END)";
    }

    /* (district, fy_start|null, n) for one point-type survey stored in road_assets */
    private List<Map<String, Object>> assetCounts(String assetType, String dateAttr) {
        return jdbc.queryForList(
            "SELECT " + DIST + " AS district, " + fyExpr(dateAttr) + " AS fy, COUNT(*) AS n " +
            "FROM road_assets a LEFT JOIN roads r ON r.\"Section_La\" = a.section_label " +
            "WHERE a.asset_type = ? GROUP BY 1, 2", assetType);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {

        /* ---- dated streams ---- */
        List<Map<String, Object>> fwd  = assetCounts("fwd", "Survey Start Date");
        List<Map<String, Object>> soil = assetCounts("subgrade", "Date");
        List<Map<String, Object>> core = assetCounts("bituminous_core", "Date");

        /* default cycle for undated streams = modal fy of the dated ones */
        Map<Integer, Long> fyVotes = new HashMap<>();
        for (List<Map<String, Object>> l : List.of(fwd, soil, core))
            for (Map<String, Object> row : l) {
                Number fy = (Number) row.get("fy");
                if (fy != null) fyVotes.merge(fy.intValue(), ((Number) row.get("n")).longValue(), Long::sum);
            }
        int defaultFy = fyVotes.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
            .orElseGet(() -> {
                LocalDate now = LocalDate.now();
                return now.getMonthValue() >= 4 ? now.getYear() : now.getYear() - 1;
            });

        /* ---- undated streams: NSV condition lane-km and traffic stations ---- */
        List<Map<String, Object>> nsv = jdbc.queryForList(
            "SELECT " + DIST + " AS district, " +
            "       ROUND(SUM(GREATEST(c.end_chainage - c.start_chainage, 0))::numeric/1000, 1) AS lane_km " +
            "FROM condition c LEFT JOIN roads r ON r.\"Section_La\" = c.section_label " +
            "GROUP BY 1");

        /* A dual-carriageway station is stored as two rows whose names differ only
           by a trailing A/B after the station number (TVM_STN_021A / TVM_STN_021B):
           count the pair once by grouping on the name with that suffix stripped. */
        List<Map<String, Object>> traffic;
        try {
            traffic = jdbc.queryForList(
                "SELECT district, COUNT(DISTINCT base_name) AS n FROM (" +
                "  SELECT " + DIST + " AS district, " +
                "         regexp_replace(trim(t.name), '([0-9])[ABab]$', '\\1') AS base_name " +
                "  FROM traffic_stations t LEFT JOIN roads r ON r.\"Section_La\" = t.section" +
                ") x GROUP BY 1");
        } catch (Exception e) {
            traffic = Collections.emptyList();
        }

        /* ---- merge: year -> district -> metric map ---- */
        // TreeMap(desc) so the newest survey cycle is listed first
        Map<Integer, Map<String, Map<String, Object>>> years = new TreeMap<>(Comparator.reverseOrder());

        add(years, fwd,  "fwd_points",       defaultFy);
        add(years, soil, "subgrade_tests",   defaultFy);
        add(years, core, "bituminous_cores", defaultFy);
        for (Map<String, Object> row : traffic)
            bump(years, defaultFy, (String) row.get("district"), "traffic_stations",
                 ((Number) row.get("n")).doubleValue());
        for (Map<String, Object> row : nsv)
            bump(years, defaultFy, (String) row.get("district"), "nsv_lane_km",
                 ((Number) row.get("lane_km")).doubleValue());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Map<String, Object>>> ye : years.entrySet()) {
            Map<String, Object> year = new LinkedHashMap<>();
            year.put("year", fyLabel(ye.getKey()));

            Map<String, Object> totals = blankMetrics();
            List<Map<String, Object>> dists = new ArrayList<>();
            ye.getValue().entrySet().stream()
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
            year.put("totals", totals);
            year.put("districts", dists);
            out.add(year);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("default_year", fyLabel(defaultFy));
        res.put("years", out);
        return res;
    }

    private static final List<String> METRICS = List.of(
        "nsv_lane_km", "fwd_points", "traffic_stations", "subgrade_tests", "bituminous_cores");

    private static Map<String, Object> blankMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : METRICS) m.put(k, k.equals("nsv_lane_km") ? 0.0 : 0L);
        return m;
    }

    private static void add(Map<Integer, Map<String, Map<String, Object>>> years,
                            List<Map<String, Object>> rows, String metric, int defaultFy) {
        for (Map<String, Object> row : rows) {
            Number fy = (Number) row.get("fy");
            bump(years, fy != null ? fy.intValue() : defaultFy,
                 (String) row.get("district"), metric, ((Number) row.get("n")).doubleValue());
        }
    }

    private static void bump(Map<Integer, Map<String, Map<String, Object>>> years,
                             int fy, String district, String metric, double v) {
        years.computeIfAbsent(fy, k -> new TreeMap<>())
             .computeIfAbsent(district == null ? "(unmapped)" : district, k -> new LinkedHashMap<>())
             .merge(metric, v, (a, b) -> ((Number) a).doubleValue() + ((Number) b).doubleValue());
    }
}
