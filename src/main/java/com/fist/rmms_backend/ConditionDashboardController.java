package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Condition Dashboard figures — state-wide and district-wise Low / High / Mean
 * for a single raw condition parameter (IRI, cracking, potholes, rutting,
 * texture, patch work, ravelling), split by pavement surface type
 * (roads."Cons_Type") and by road class (roads."Road_Class"), for one survey
 * period.
 *
 * Both splits are taken from the road network's own attributes rather than from
 * a list of expected codes held here, so whatever the network carries is what
 * the dashboard reports. Codes are expanded for display through the Lookup
 * &amp; Short Code module.
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

    private static final Logger log = LoggerFactory.getLogger(ConditionDashboardController.class);

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final CalcRuleService rules;
    private final LookupService lookups;

    public ConditionDashboardController(JdbcTemplate jdbc, SurveyPeriodService periods,
                                        CalcRuleService rules, LookupService lookups) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.rules = rules;
        this.lookups = lookups;
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
    /* Pavement surface and road class are ATTRIBUTES OF THE ROAD NETWORK, joined
       to a condition stretch through its Section_La. This groups on the value the
       road data actually holds — no CASE statement listing the codes it expects,
       so a Cons_Type the network starts carrying appears as its own bucket
       instead of being swept into "Other" by a rule nobody remembered to update.

       The reader still sees "Flexible" rather than FLX: the code is expanded
       through the Lookup & Short Code module, which is where the meaning of a
       short code is defined for the whole system (LookupService.displayLabels).
       A code with no lookup entry is shown as itself — visible and countable,
       which is what makes a missing entry easy to spot. */
    private static final String SURFACE =
        "COALESCE(NULLIF(trim(r.\"Cons_Type\"),''),'(unspecified)')";
    private static final String ROAD_CLASS =
        "COALESCE(NULLIF(trim(r.\"Road_Class\"),''),'(unspecified)')";

    /* Carriageway width for the area weighting IS a calculation rule — the metres
       behind a band code are a constant somebody has to choose, not something the
       data states. It comes from the Calculation Rules module: the band's metres,
       and the share of them one centreline of a dual road takes (the band
       describes the whole road, so counting both halves at full width would count
       the road's area twice). */
    private String widthSql() { return rules.widthSql(); }

    /** Stored value -> the label to show for it, for one roads attribute. */
    private Map<String, String> labelsFor(String attribute) {
        try {
            return lookups.displayLabels("roads", "default", attribute);
        } catch (Exception e) {
            log.debug("No lookup labels for roads.{}", attribute, e);
            return Map.of();
        }
    }

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

        /* The buckets are whatever the road network actually holds, in the order
           the lookup list defines (falling back to alphabetical for a code the
           list has not got), rather than a fixed set declared up here. */
        List<String> SURFACES = bucketsIn(rows, "surface", labelsFor("Cons_Type"));
        List<String> CLASSES  = bucketsIn(rows, "road_class", labelsFor("Road_Class"));

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
            stateBySurface.computeIfAbsent(sf, k -> new Stat()).merge(s);
            stateByClass.computeIfAbsent(cl, k -> new Stat()).merge(s);
            matrix.computeIfAbsent(sf, k -> blank(CLASSES)).computeIfAbsent(cl, k -> new Stat()).merge(s);
            distAll.computeIfAbsent(d, k -> new Stat()).merge(s);
            distBySurface.computeIfAbsent(d, k -> blank(SURFACES)).computeIfAbsent(sf, k -> new Stat()).merge(s);
            distByClass.computeIfAbsent(d, k -> blank(CLASSES)).computeIfAbsent(cl, k -> new Stat()).merge(s);
        }

        Map<String, String> sfLabels = labelsFor("Cons_Type");
        Map<String, String> clLabels = labelsFor("Road_Class");

        Map<String, Object> statewide = new LinkedHashMap<>();
        statewide.put("overall", stateAll.toMap());
        statewide.put("by_surface", listOf(SURFACES, stateBySurface, "surface", sfLabels));
        statewide.put("by_class", listOf(CLASSES, stateByClass, "road_class", clLabels));
        List<Map<String, Object>> mtx = new ArrayList<>();
        for (String sf : SURFACES) {
            Map<String, Stat> byCl = matrix.getOrDefault(sf, blank(CLASSES));
            for (String cl : CLASSES) {
                if (byCl.get(cl).n == 0) continue;
                Map<String, Object> m = byCl.get(cl).toMap();
                m.put("surface", sf);
                m.put("surface_label", LookupService.label(sfLabels, sf));
                m.put("road_class", cl);
                m.put("road_class_label", LookupService.label(clLabels, cl));
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
            dm.put("by_surface", listOf(SURFACES, distBySurface.getOrDefault(d, blank(SURFACES)), "surface", sfLabels));
            dm.put("by_class", listOf(CLASSES, distByClass.getOrDefault(d, blank(CLASSES)), "road_class", clLabels));
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
        /* The buckets the screen offers as filters: whatever the network holds,
           already decoded. Sent so the browser never has to know a code list. */
        res.put("surfaces", catalog(SURFACES, sfLabels));
        res.put("classes", catalog(CLASSES, clLabels));
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
        /* Both filters take the value as the road network stores it — the same
           value /summary handed out as the bucket key, so a dropdown built from
           that response round-trips without any translation on either side. */
        if (surface != null && !surface.isBlank()) {
            where.append(" AND ").append(SURFACE).append(" = ?");
            args.add(surface.trim());
        }
        if (road_class != null && !road_class.isBlank()) {
            where.append(" AND ").append(ROAD_CLASS).append(" = ?");
            args.add(road_class.trim());
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
            "       ROUND((SUM(cs." + vcol + " * (cs.end_chainage - cs.start_chainage) * (" + widthSql() + ")) / " +
            "              NULLIF(SUM((cs.end_chainage - cs.start_chainage) * (" + widthSql() + ")), 0))::numeric, 2) AS value, " +
            "       ROUND(MAX(cs." + vcol + ")::numeric, 2) AS peak, " +
            "       ROUND((SUM((cs.end_chainage - cs.start_chainage) * COALESCE(cs.lane_count,1)) / 1000.0)::numeric, 1) AS lane_km, " +
            "       COUNT(*) AS segments " +
            "FROM condition_segments cs JOIN roads r ON r.\"Section_La\" = cs.section_label " +
            CalcRuleService.RULE_JOINS +
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

    /* Rows carry the STORED value as their key so a filter can send it straight
       back, and the decoded label alongside it so the screen can read "Flexible"
       without the browser holding its own copy of the code list. */
    private static List<Map<String, Object>> listOf(List<String> keys, Map<String, Stat> src,
                                                    String keyName, Map<String, String> labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys) {
            Stat s = src.getOrDefault(k, new Stat());
            Map<String, Object> m = s.toMap();
            m.put(keyName, k);
            m.put("label", LookupService.label(labels, k));
            out.add(m);
        }
        return out;
    }

    /**
     * The distinct values one grouped column actually holds, ordered by the
     * lookup list where there is one and alphabetically for anything it does not
     * name — so a code nobody has defined yet still gets its own bucket at the
     * end rather than disappearing into a catch-all.
     */
    private static List<String> bucketsIn(List<Map<String, Object>> rows, String col,
                                          Map<String, String> labels) {
        Set<String> present = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(col);
            if (v != null) present.add(String.valueOf(v));
        }
        List<String> known = new ArrayList<>(), unknown = new ArrayList<>();
        for (String v : present) {
            (labels.containsKey(v) ? known : unknown).add(v);
        }
        List<String> order = new ArrayList<>(labels.keySet());
        known.sort(Comparator.comparingInt(order::indexOf));
        Collections.sort(unknown);
        known.addAll(unknown);
        return known;
    }

    /** The bucket catalogue the screen builds its filter dropdowns from. */
    private static List<Map<String, Object>> catalog(List<String> keys, Map<String, String> labels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", k);
            m.put("label", LookupService.label(labels, k));
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
