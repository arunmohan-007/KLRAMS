package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Condition Dashboard figures — state-wide and district-wise Low / High / Mean
 * for a single raw condition parameter (IRI, cracking, potholes, rutting,
 * texture, patch work, ravelling), split by pavement surface type
 * (Flexible / Cement Concrete / Paver Block, from roads."Cons_Type") and by
 * road class (SH / MDR), for one survey period.
 *
 * The condition parameters live per stretch in {@code condition_segments}
 * (built by {@link SegmentService}) in two forms:
 *   basis=avg   -> avg_&lt;param&gt;  (average across the carriageway lanes)
 *   basis=worst -> &lt;param&gt;      (worst / MAX lane on the stretch)
 * The chosen column is the per-segment "condition value"; Low = MIN of it over
 * the group, High = MAX, Mean = length-weighted average (weighted by the
 * segment's centreline length so a long stretch counts more than a short one).
 *
 * District/class/surface come from the segment's road via section label, exactly
 * like {@link SurveyDashboardController} and {@link FwdDashboardController}.
 */
@RestController
@RequestMapping("/api/condition-dashboard")
public class ConditionDashboardController {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    public ConditionDashboardController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    /* Parameter whitelist — guards the column name that is interpolated into SQL.
       label/unit mirror the frontend PARAMS table (01-config.js). */
    private static final Map<String, String[]> PARAMS = new LinkedHashMap<>();
    static {
        PARAMS.put("iri",        new String[]{"IRI",        "m/km"});
        PARAMS.put("crack",      new String[]{"Cracking",   "%"});
        PARAMS.put("pothole",    new String[]{"Pothole",    "nos/km"});
        PARAMS.put("rutting",    new String[]{"Rutting",    "mm"});
        PARAMS.put("texture",    new String[]{"Texture",    "mm"});
        PARAMS.put("patch_work", new String[]{"Patch work", "sqm"});
        PARAMS.put("ravelling",  new String[]{"Ravelling",  "%"});
    }

    private static final String DIST =
        "COALESCE(NULLIF(trim(r.\"District\"),''),'(unmapped)')";
    /* Cons_Type codes -> the three requested surface families (everything else is Other) */
    private static final String SURFACE =
        "CASE upper(trim(r.\"Cons_Type\")) " +
        "  WHEN 'FLX' THEN 'Flexible' " +
        "  WHEN 'RGD' THEN 'Cement Concrete' " +
        "  WHEN 'PVB' THEN 'Paver Block' " +
        "  ELSE 'Other' END";
    private static final String ROAD_CLASS =
        "CASE upper(trim(r.\"Road_Class\")) " +
        "  WHEN 'SH' THEN 'SH' WHEN 'MDR' THEN 'MDR' ELSE 'Other' END";
    /* Carriageway width in metres from the Pavement_W band code (1-5), matching
       10-pci-report.js PVMT_W_M; 7 m default when the code is absent. Used to
       area-weight (area = stretch length × width) the top-roads ranking.
       Dual-carriageway correction: a dual road is drawn as TWO centrelines (A/B)
       but Pavement_W describes the ENTIRE road (e.g. a 4-lane dual carries code 5
       on both halves), so each A/B line gets HALF the banded width — otherwise
       the road's area is counted twice. */
    private static final String WIDTH_M =
        "(CASE trim(r.\"Pavement_W\"::text) " +
        "   WHEN '1' THEN 4.5 WHEN '2' THEN 6.25 WHEN '3' THEN 8.5 " +
        "   WHEN '4' THEN 11.5 WHEN '5' THEN 14 ELSE 7 END) " +
        " * (CASE WHEN lower(trim(r.\"Single_Du\")) = 'dual' THEN 0.5 ELSE 1 END)";

    private static final List<String> SURFACES = List.of("Flexible", "Cement Concrete", "Paver Block", "Other");
    private static final List<String> CLASSES  = List.of("SH", "MDR", "Other");

    /** Column for the chosen basis, after validating the parameter. */
    private String valueColumn(String param, String basis) {
        if (!PARAMS.containsKey(param)) throw new IllegalArgumentException("Unknown parameter: " + param);
        return "worst".equalsIgnoreCase(basis) ? param : "avg_" + param;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "iri") String param,
            @RequestParam(defaultValue = "avg") String basis,
            @RequestParam(required = false) Integer period_id) {

        String vcol = valueColumn(param, basis);
        int pid = periods.resolve(period_id);

        /* One grouped pass: (district, surface, class) -> low/high/Σvl/Σlen/Σlane_m/n.
           len = centreline metres of the stretch; lane_m = len × lane_count. */
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT " + DIST + " AS district, " + SURFACE + " AS surface, " + ROAD_CLASS + " AS road_class, " +
            "       MIN(cs." + vcol + ") AS low, MAX(cs." + vcol + ") AS high, " +
            "       SUM(cs." + vcol + " * (cs.end_chainage - cs.start_chainage)) AS sum_vl, " +
            "       SUM(cs.end_chainage - cs.start_chainage) AS sum_len, " +
            "       SUM((cs.end_chainage - cs.start_chainage) * COALESCE(cs.lane_count,1)) AS lane_m, " +
            "       COUNT(*) AS n " +
            "FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            "WHERE cs.period_id = ? AND cs." + vcol + " IS NOT NULL AND cs.end_chainage > cs.start_chainage " +
            "GROUP BY 1,2,3", pid);

        /* Roll the grouped rows up into every view the dashboard needs. */
        Stat stateAll = new Stat();
        Map<String, Stat> stateBySurface = blank(SURFACES);
        Map<String, Stat> stateByClass   = blank(CLASSES);
        Map<String, Map<String, Stat>> matrix = new LinkedHashMap<>();       // surface -> class -> stat
        Map<String, Stat> distAll = new TreeMap<>();
        Map<String, Map<String, Stat>> distBySurface = new TreeMap<>();
        Map<String, Map<String, Stat>> distByClass   = new TreeMap<>();

        for (Map<String, Object> row : rows) {
            String d = (String) row.get("district");
            String sf = (String) row.get("surface");
            String cl = (String) row.get("road_class");
            Stat s = Stat.of(row);

            stateAll.merge(s);
            stateBySurface.get(sf).merge(s);
            stateByClass.get(cl).merge(s);
            matrix.computeIfAbsent(sf, k -> blank(CLASSES)).get(cl).merge(s);
            distAll.computeIfAbsent(d, k -> new Stat()).merge(s);
            distBySurface.computeIfAbsent(d, k -> blank(SURFACES)).get(sf).merge(s);
            distByClass.computeIfAbsent(d, k -> blank(CLASSES)).get(cl).merge(s);
        }

        Map<String, Object> statewide = new LinkedHashMap<>();
        statewide.put("overall", stateAll.toMap());
        statewide.put("by_surface", listOf(SURFACES, stateBySurface, "surface"));
        statewide.put("by_class", listOf(CLASSES, stateByClass, "road_class"));
        List<Map<String, Object>> mtx = new ArrayList<>();
        for (String sf : SURFACES) {
            Map<String, Stat> byCl = matrix.getOrDefault(sf, blank(CLASSES));
            for (String cl : CLASSES) {
                if (byCl.get(cl).n == 0) continue;
                Map<String, Object> m = byCl.get(cl).toMap();
                m.put("surface", sf);
                m.put("road_class", cl);
                mtx.add(m);
            }
        }
        statewide.put("matrix", mtx);

        List<Map<String, Object>> districts = new ArrayList<>();
        for (Map.Entry<String, Stat> e : distAll.entrySet()) {
            String d = e.getKey();
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("district", d);
            dm.put("overall", e.getValue().toMap());
            dm.put("by_surface", listOf(SURFACES, distBySurface.getOrDefault(d, blank(SURFACES)), "surface"));
            dm.put("by_class", listOf(CLASSES, distByClass.getOrDefault(d, blank(CLASSES)), "road_class"));
            districts.add(dm);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("param", param);
        res.put("param_label", PARAMS.get(param)[0]);
        res.put("param_unit", PARAMS.get(param)[1]);
        res.put("basis", "worst".equalsIgnoreCase(basis) ? "worst" : "avg");
        res.put("params", paramCatalog());
        res.put("period_id", pid);
        res.putAll(periodMeta(pid));
        res.put("statewide", statewide);
        res.put("districts", districts);
        return res;
    }

    /** The stretches whose condition value passes the threshold, for the table view. */
    @GetMapping("/table")
    public Map<String, Object> table(
            @RequestParam(defaultValue = "iri") String param,
            @RequestParam(defaultValue = "avg") String basis,
            @RequestParam(defaultValue = "gte") String op,
            @RequestParam(defaultValue = "0") double value,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String surface,
            @RequestParam(required = false) String road_class,
            @RequestParam(required = false) Integer period_id,
            @RequestParam(defaultValue = "2000") int limit) {

        String vcol = valueColumn(param, basis);
        int pid = periods.resolve(period_id);
        String cmp = switch (op) {
            case "lte" -> "<=";
            case "gt"  -> ">";
            case "lt"  -> "<";
            default    -> ">=";
        };
        boolean asc = cmp.startsWith("<");

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "cs.period_id = ? AND cs." + vcol + " IS NOT NULL AND cs.end_chainage > cs.start_chainage " +
            "AND cs." + vcol + " " + cmp + " ?");
        args.add(pid);
        args.add(value);
        if (district != null && !district.isBlank() && !"(unmapped)".equals(district)) {
            where.append(" AND trim(r.\"District\") = ?");
            args.add(district.trim());
        } else if ("(unmapped)".equals(district)) {
            where.append(" AND NULLIF(trim(r.\"District\"),'') IS NULL");
        }
        String consCode = surfaceCode(surface);
        if (consCode != null) {
            where.append(" AND upper(trim(r.\"Cons_Type\")) = ?");
            args.add(consCode);
        }
        if (road_class != null && (road_class.equalsIgnoreCase("SH") || road_class.equalsIgnoreCase("MDR"))) {
            where.append(" AND upper(trim(r.\"Road_Class\")) = ?");
            args.add(road_class.toUpperCase());
        }

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            "WHERE " + where, Long.class, args.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(Math.max(1, Math.min(limit, 20000)));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT cs.section_label AS section_label, " +
            "       r.\"Road_Class\" AS road_class, r.\"Road_Name\" AS road_name, r.\"Road_Num\" AS road_num, " +
            "       cs.start_chainage AS from_ch, cs.end_chainage AS to_ch, " +
            "       ROUND((((cs.end_chainage - cs.start_chainage) * COALESCE(cs.lane_count,1)) / 1000.0)::numeric, 3) AS lane_km, " +
            "       cs.xsp_list AS xsp, " +
            "       ROUND(cs." + vcol + "::numeric, 2) AS value " +
            "FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            "WHERE " + where + " ORDER BY value " + (asc ? "ASC" : "DESC") + ", section_label LIMIT ?",
            rowArgs.toArray());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("param", param);
        res.put("param_label", PARAMS.get(param)[0]);
        res.put("param_unit", PARAMS.get(param)[1]);
        res.put("basis", "worst".equalsIgnoreCase(basis) ? "worst" : "avg");
        res.put("op", cmp);
        res.put("value", value);
        res.put("total", total == null ? 0 : total);
        res.put("returned", rows.size());
        res.put("rows", rows);
        return res;
    }

    /**
     * The worst-ranked individual roads for the selected parameter — Top N SH
     * and Top N MDR by the road's <b>area-weighted average</b> condition value
     * (area = stretch length × carriageway width from the Pavement_W band, so a
     * wide/long stretch pulls the road average more than a short narrow one).
     *
     * SH roads are grouped by <b>Road Number</b>, falling back to Road Name when
     * a stretch carries no number (matches the SH counting rule in
     * {@link DashboardController}); MDR roads are grouped by Road Name. Ranked
     * highest value first (higher = worse condition for every parameter). Honours
     * the current district scope when a district is passed.
     */
    @GetMapping("/top-roads")
    public Map<String, Object> topRoads(
            @RequestParam(defaultValue = "iri") String param,
            @RequestParam(defaultValue = "avg") String basis,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) Integer period_id,
            @RequestParam(defaultValue = "10") int sh,
            @RequestParam(defaultValue = "5") int mdr,
            @RequestParam(defaultValue = "5") int sh_sec,
            @RequestParam(defaultValue = "5") int mdr_sec) {

        String vcol = valueColumn(param, basis);
        int pid = periods.resolve(period_id);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("param", param);
        res.put("param_label", PARAMS.get(param)[0]);
        res.put("param_unit", PARAMS.get(param)[1]);
        res.put("basis", "worst".equalsIgnoreCase(basis) ? "worst" : "avg");
        // Road-wise: one row per road (grouped by road number / name).
        res.put("sh", topFor("SH", vcol, pid, district, clamp(sh)));
        res.put("mdr", topFor("MDR", vcol, pid, district, clamp(mdr)));
        // Section-wise: one row per individual section label (finer granularity).
        res.put("sh_sections", topSectionsFor("SH", vcol, pid, district, clamp(sh_sec)));
        res.put("mdr_sections", topSectionsFor("MDR", vcol, pid, district, clamp(mdr_sec)));
        return res;
    }

    private List<Map<String, Object>> topFor(String cls, String vcol, int pid, String district, int limit) {
        // Road_Num is a numeric column, so cast to text before trimming.
        String roadKey = "SH".equals(cls)
            ? "COALESCE(NULLIF(trim(r.\"Road_Num\"::text),''), NULLIF(trim(r.\"Road_Name\"),''))"
            : "NULLIF(trim(r.\"Road_Name\"),'')";

        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "cs.period_id = ? AND cs." + vcol + " IS NOT NULL AND cs.end_chainage > cs.start_chainage " +
            "AND upper(trim(r.\"Road_Class\")) = ? AND " + roadKey + " IS NOT NULL");
        args.add(pid);
        args.add(cls);
        if (district != null && !district.isBlank() && !"(unmapped)".equals(district)) {
            where.append(" AND trim(r.\"District\") = ?");
            args.add(district.trim());
        } else if ("(unmapped)".equals(district)) {
            where.append(" AND NULLIF(trim(r.\"District\"),'') IS NULL");
        }
        args.add(limit);

        return jdbc.queryForList(
            "SELECT " + roadKey + " AS road_key, " +
            "       MAX(NULLIF(trim(r.\"Road_Num\"::text),'')) AS road_num, " +
            "       string_agg(DISTINCT NULLIF(trim(r.\"Road_Name\"),''), ' · ') AS road_names, " +
            "       string_agg(DISTINCT NULLIF(trim(r.\"District\"),''), ', ') AS districts, " +
            "       ROUND((SUM(cs." + vcol + " * (cs.end_chainage - cs.start_chainage) * (" + WIDTH_M + ")) / " +
            "              NULLIF(SUM((cs.end_chainage - cs.start_chainage) * (" + WIDTH_M + ")), 0))::numeric, 2) AS value, " +
            "       ROUND(MAX(cs." + vcol + ")::numeric, 2) AS peak, " +
            "       ROUND((SUM((cs.end_chainage - cs.start_chainage) * COALESCE(cs.lane_count,1)) / 1000.0)::numeric, 1) AS lane_km, " +
            "       COUNT(*) AS segments " +
            "FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            "WHERE " + where + " " +
            "GROUP BY " + roadKey + " " +
            "ORDER BY value DESC NULLS LAST, lane_km DESC LIMIT ?",
            args.toArray());
    }

    /**
     * The worst-ranked individual <b>section labels</b> for the selected class,
     * finer-grained than {@link #topFor} which rolls a whole road into one row.
     * Each row is one {@code Section_La}; the value is its length-weighted average
     * condition value (width is constant within a section, so length-weighting is
     * equivalent to area-weighting here). Ranked highest value first.
     */
    private List<Map<String, Object>> topSectionsFor(String cls, String vcol, int pid, String district, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(
            "cs.period_id = ? AND cs." + vcol + " IS NOT NULL AND cs.end_chainage > cs.start_chainage " +
            "AND upper(trim(r.\"Road_Class\")) = ?");
        args.add(pid);
        args.add(cls);
        if (district != null && !district.isBlank() && !"(unmapped)".equals(district)) {
            where.append(" AND trim(r.\"District\") = ?");
            args.add(district.trim());
        } else if ("(unmapped)".equals(district)) {
            where.append(" AND NULLIF(trim(r.\"District\"),'') IS NULL");
        }
        args.add(limit);

        return jdbc.queryForList(
            "SELECT cs.section_label AS section_label, " +
            "       MAX(NULLIF(trim(r.\"Road_Num\"::text),'')) AS road_num, " +
            "       MAX(NULLIF(trim(r.\"Road_Name\"),'')) AS road_name, " +
            "       MAX(NULLIF(trim(r.\"District\"),'')) AS district, " +
            "       ROUND((SUM(cs." + vcol + " * (cs.end_chainage - cs.start_chainage)) / " +
            "              NULLIF(SUM(cs.end_chainage - cs.start_chainage), 0))::numeric, 2) AS value, " +
            "       ROUND(MAX(cs." + vcol + ")::numeric, 2) AS peak, " +
            "       MIN(cs.start_chainage) AS from_ch, MAX(cs.end_chainage) AS to_ch, " +
            "       ROUND((SUM((cs.end_chainage - cs.start_chainage) * COALESCE(cs.lane_count,1)) / 1000.0)::numeric, 1) AS lane_km, " +
            "       COUNT(*) AS segments " +
            "FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            "WHERE " + where + " " +
            "GROUP BY cs.section_label " +
            "ORDER BY value DESC NULLS LAST, lane_km DESC LIMIT ?",
            args.toArray());
    }

    private static int clamp(int n) { return Math.max(1, Math.min(n, 50)); }

    /* ---- helpers ---- */

    private static String surfaceCode(String surface) {
        if (surface == null || surface.isBlank()) return null;
        return switch (surface.trim().toLowerCase()) {
            case "flexible" -> "FLX";
            case "cement concrete", "rigid" -> "RGD";
            case "paver block" -> "PVB";
            default -> null;
        };
    }

    private List<Map<String, Object>> paramCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        PARAMS.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("label", v[0]);
            m.put("unit", v[1]);
            out.add(m);
        });
        return out;
    }

    private Map<String, Object> periodMeta(int pid) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> ps = new ArrayList<>();
        Map<String, Object> def = null;
        int activeId = periods.activePeriodId();
        for (Map<String, Object> p : periods.list()) {
            int id = ((Number) p.get("id")).intValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", p.get("name"));
            Object s = p.get("start_date"), e = p.get("end_date");
            row.put("range", (s == null && e == null) ? "" :
                    (s == null ? "…" : s) + " – " + (e == null ? "…" : e));
            row.put("is_active", Boolean.TRUE.equals(p.get("is_active")));
            ps.add(row);
            if (id == activeId) def = Map.of("id", id, "name", p.get("name"));
        }
        out.put("periods", ps);
        out.put("default_period", def);
        return out;
    }

    private static Map<String, Stat> blank(List<String> keys) {
        Map<String, Stat> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, new Stat());
        return m;
    }

    private static List<Map<String, Object>> listOf(List<String> keys, Map<String, Stat> src, String keyName) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys) {
            Stat s = src.getOrDefault(k, new Stat());
            Map<String, Object> m = s.toMap();
            m.put(keyName, k);
            out.add(m);
        }
        return out;
    }

    /** Rolling accumulator for Low / High / length-weighted Mean / lane-km / segment count. */
    private static final class Stat {
        long n = 0;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        double sumVl = 0, sumLen = 0, laneM = 0;

        static Stat of(Map<String, Object> row) {
            Stat s = new Stat();
            s.n = ((Number) row.get("n")).longValue();
            s.low = dbl(row.get("low"));
            s.high = dbl(row.get("high"));
            s.sumVl = dbl(row.get("sum_vl"));
            s.sumLen = dbl(row.get("sum_len"));
            s.laneM = dbl(row.get("lane_m"));
            return s;
        }

        void merge(Stat o) {
            if (o.n == 0) return;
            n += o.n;
            low = Math.min(low, o.low);
            high = Math.max(high, o.high);
            sumVl += o.sumVl;
            sumLen += o.sumLen;
            laneM += o.laneM;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("segments", n);
            m.put("low", n == 0 ? null : r2(low));
            m.put("high", n == 0 ? null : r2(high));
            m.put("mean", (n == 0 || sumLen <= 0) ? null : r2(sumVl / sumLen));
            m.put("lane_km", Math.round(laneM / 1000.0 * 10) / 10.0);
            return m;
        }

        private static double dbl(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }
        private static double r2(double v) { return Math.round(v * 100) / 100.0; }
    }
}
