package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FWD Dashboard figures — per survey period (see {@link SurveyPeriodService}):
 * point counts, D0 deflection statistics (min / max / mean / percentiles),
 * the full lower-to-higher D0 profile and a binned histogram — everything
 * split by road class (SH / MDR / …) and by district. Also ranks individual
 * road sections by their own mean D0 (see {@link #weakSections}), since the
 * district/class rollups pool many different roads and aren't a substitute
 * for a per-road design value.
 *
 * Pavement and air temperature are read from the FWD upload's kept attributes
 * (attrs jsonb) by fuzzy key match — any column whose normalised name contains
 * "pavementtemp" / "surfacetemp" or "airtemp" — so the moment a survey file
 * carrying those columns is imported, the temperature figures light up without
 * a code change. When no such column exists the payload says so (null).
 *
 * The raw FWD table is small (a few thousand points), so one SELECT pulls the
 * per-point tuples and all aggregation happens here: that keeps the SQL trivial
 * and lets us build sorted profiles / histograms per scope in one pass.
 * District comes from roads."District" via the record's section label, exactly
 * like {@link SurveyDashboardController}.
 */
@RestController
@RequestMapping("/api/fwd-dashboard")
public class FwdDashboardController {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    public FwdDashboardController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    /* One FWD test point, already joined to the road network.
       surf = FLEXIBLE / RIGID / UNKNOWN (see surfOf). */
    private record Pt(String district, String cls, String surf, String section, Double d0, Double pav, Double air) {}

    private static final String NUM = "'^-?[0-9]+(\\.[0-9]+)?$'";

    private static final String SQL = """
        SELECT a.period_id AS pid,
               a.section_label AS section,
               COALESCE(NULLIF(trim(r."District"),''), '(unmapped)')      AS district,
               COALESCE(NULLIF(upper(trim(r."Road_Class")),''), 'OTHER')  AS cls,
               r."Cons_Type"  AS cons_type,
               r."Surface_Ty" AS surf_type,
               (SELECT e.value
                  FROM jsonb_each_text(a.attrs) e
                 WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g')
                       ~ '(pavement.*type|type.*pavement|construction.*type)'
                 LIMIT 1) AS ptype,
               (SELECT (e.value)::double precision
                  FROM jsonb_each_text(a.attrs) e
                 WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN ('d0','do')
                   AND e.value ~ %s LIMIT 1) AS d0,
               (SELECT (e.value)::double precision
                  FROM jsonb_each_text(a.attrs) e
                 WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') ~ '(pavement|surface)temp'
                   AND e.value ~ %s LIMIT 1) AS pav,
               (SELECT (e.value)::double precision
                  FROM jsonb_each_text(a.attrs) e
                 WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') ~ 'airtemp'
                   AND e.value ~ %s LIMIT 1) AS air
        FROM road_assets a
        LEFT JOIN roads r ON r."Section_La" = a.section_label
        WHERE a.asset_type = 'fwd' AND a.period_id IS NOT NULL
        """.formatted(NUM, NUM, NUM);

    private static final int PROFILE_MAX = 140;   // points per lower→higher curve

    @GetMapping("/summary")
    public Map<String, Object> summary() {

        Map<Integer, List<Pt>> byPeriod = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(SQL)) {
            Number pid = (Number) row.get("pid");
            if (pid == null) continue;
            byPeriod.computeIfAbsent(pid.intValue(), k -> new ArrayList<>()).add(new Pt(
                (String) row.get("district"), (String) row.get("cls"),
                surfOf((String) row.get("ptype"), (String) row.get("cons_type"), (String) row.get("surf_type")),
                (String) row.get("section"),
                (Double) row.get("d0"), (Double) row.get("pav"), (Double) row.get("air")));
        }

        int activeId = periods.activePeriodId();
        Map<String, Object> defaultPeriod = null;
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> p : periods.list()) {
            int pid = ((Number) p.get("id")).intValue();
            List<Pt> pts = byPeriod.getOrDefault(pid, Collections.emptyList());

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("id", pid);
            period.put("name", p.get("name"));
            period.put("range", rangeLabel(p));
            period.put("is_active", Boolean.TRUE.equals(p.get("is_active")));
            period.put("points", pts.size());

            /* Rigid (CC / PQC) slabs deflect an order of magnitude less than
               flexible (BT) pavement, so every figure is also computed per
               pavement type — each variant gets its OWN bin edges and profile
               scale, otherwise the rigid histogram is squashed against the
               flexible range and both statistics are distorted. */
            Map<String, Object> mix = new LinkedHashMap<>();
            Map<String, Object> variants = new LinkedHashMap<>();
            variants.put("all", variantStats(pts));
            for (String sf : List.of("FLEXIBLE", "RIGID", "UNKNOWN")) {
                List<Pt> sub = pts.stream().filter(x -> sf.equals(x.surf())).collect(Collectors.toList());
                mix.put(sf.toLowerCase(), sub.size());
                if (!sub.isEmpty()) variants.put(sf.toLowerCase(), variantStats(sub));
            }
            period.put("surface_mix", mix);
            period.put("variants", variants);

            out.add(period);
            if (pid == activeId) defaultPeriod = Map.of("id", pid, "name", p.get("name"));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("default_period", defaultPeriod);
        res.put("periods", out);
        return res;
    }

    /** Lists the FWD points behind the "OTHER" road-class / "(unmapped)" district
     *  bucket: rows whose section label matches no roads."Section_La" (these were
     *  kept at import because they carried GPS coordinates — see AssetController)
     *  plus rows whose matched road has a blank Road_Class. For unmatched labels a
     *  suggestion is looked up by comparing labels with all non-alphanumerics
     *  stripped, which catches case / spacing / punctuation typos. */
    @GetMapping("/unmapped")
    public List<Map<String, Object>> unmapped(@RequestParam(required = false) Integer period_id) {
        int pid = period_id != null ? period_id : periods.activePeriodId();
        return jdbc.queryForList("""
            SELECT a.id, a.section_label, a.start_chainage, a.end_chainage,
                   CASE WHEN GeometryType(a.geom) = 'POINT' THEN round(ST_Y(a.geom)::numeric, 6) END AS lat,
                   CASE WHEN GeometryType(a.geom) = 'POINT' THEN round(ST_X(a.geom)::numeric, 6) END AS lng,
                   CASE WHEN r."Section_La" IS NULL THEN 'no_road' ELSE 'blank_class' END AS reason,
                   CASE WHEN r."Section_La" IS NULL THEN
                       (SELECT r2."Section_La" FROM roads r2
                         WHERE regexp_replace(upper(r2."Section_La"), '[^A-Z0-9]', '', 'g')
                             = regexp_replace(upper(a.section_label), '[^A-Z0-9]', '', 'g')
                         LIMIT 1)
                   END AS suggestion
            FROM road_assets a
            LEFT JOIN roads r ON r."Section_La" = a.section_label
            WHERE a.asset_type = 'fwd' AND a.period_id = ?
              AND (r."Section_La" IS NULL OR NULLIF(upper(trim(r."Road_Class")), '') IS NULL)
            ORDER BY a.section_label, a.start_chainage
            """, pid);
    }

    /* ---- full stats block for one point set: period-level figures plus the
       district split — built for 'all' and for each pavement-type variant ---- */
    private static Map<String, Object> variantStats(List<Pt> pts) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("points", pts.size());
        /* Bin edges are fixed per variant so every histogram (overall and
           per district) shares the same x axis and can be compared. */
        double[] edges = binEdges(pts);
        v.put("hist_edges", round3(edges));
        v.putAll(scopeStats(pts, edges));
        v.put("weak_sections", weakSections(pts));
        List<Map<String, Object>> dists = new ArrayList<>();
        pts.stream().collect(Collectors.groupingBy(Pt::district, TreeMap::new, Collectors.toList()))
           .forEach((district, dpts) -> {
               Map<String, Object> d = new LinkedHashMap<>();
               d.put("district", district);
               d.put("points", dpts.size());
               d.putAll(scopeStats(dpts, edges));
               dists.add(d);
           });
        v.put("districts", dists);
        return v;
    }

    /** FLEXIBLE / RIGID / UNKNOWN — from the FWD file's own pavement-type column
     *  when present (fuzzy key match, like the temperatures), else the matched
     *  road's construction type, else its surface type. Values are compared with
     *  all non-letters stripped so BT / B.T. / "Bituminous", FLX / Flexible,
     *  CC / C.C. / PQC / RCC / RGD / "Rigid" / "Concrete" all classify. */
    private static String surfOf(String... vals) {
        for (String v : vals) {
            if (v == null) continue;
            String n = v.toUpperCase().replaceAll("[^A-Z]", "");
            if (n.isEmpty()) continue;
            if (n.contains("RIG") || n.contains("CONC") || n.equals("RGD")
                || n.equals("CC") || n.equals("PQC") || n.equals("RCC")) return "RIGID";
            if (n.contains("FLEX") || n.contains("FLX") || n.equals("BT")
                || n.contains("BITUM")) return "FLEXIBLE";
        }
        return "UNKNOWN";
    }

    /* ---- everything computed for one scope (whole period or one district) ---- */
    private static Map<String, Object> scopeStats(List<Pt> pts, double[] edges) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("d0", d0Stats(pts));
        s.put("temps", tempStats(pts));

        Map<String, List<Pt>> byCls = pts.stream()
            .collect(Collectors.groupingBy(Pt::cls, TreeMap::new, Collectors.toList()));

        List<Map<String, Object>> classes = new ArrayList<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        Map<String, Object> hist = new LinkedHashMap<>();
        byCls.forEach((cls, cpts) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cls", cls);
            c.put("points", cpts.size());
            c.put("d0", d0Stats(cpts));
            classes.add(c);
            double[] sorted = sortedD0(cpts);
            if (sorted.length > 0) {
                profile.put(cls, round3(decimate(sorted, PROFILE_MAX)));
                hist.put(cls, histogram(sorted, edges));
            }
        });
        s.put("classes", classes);
        s.put("profile", profile);
        s.put("hist", hist);
        return s;
    }

    private static double[] sortedD0(List<Pt> pts) {
        return pts.stream().filter(p -> p.d0 != null).mapToDouble(Pt::d0).sorted().toArray();
    }

    private static Map<String, Object> d0Stats(List<Pt> pts) {
        double[] v = sortedD0(pts);
        if (v.length == 0) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", v.length);
        m.put("min", r3(v[0]));
        m.put("max", r3(v[v.length - 1]));
        m.put("mean", r3(Arrays.stream(v).average().orElse(0)));
        m.put("p50", r3(pct(v, 0.50)));
        m.put("p90", r3(pct(v, 0.90)));
        /* Sorted, decimated D0 curve for this scope — lets the dashboard compute an
           arbitrary "% of points above cutoff X" client-side without a round trip. */
        m.put("curve", round3(decimate(v, PROFILE_MAX)));
        return m;
    }

    private static final int WEAK_SECTIONS_MAX = 15;
    private static final int WEAK_SECTIONS_MIN_PTS = 3;

    /** Individual road sections ranked by their OWN mean D0 (highest/weakest first).
     *  Unlike the district/class rollups — which pool many different roads and make
     *  a percentile meaningless for design — this lets a road be picked out for a
     *  proper per-road IRC:115 evaluation. Sections need at least a few points so a
     *  single noisy reading can't put a road at the top of the list. */
    private static List<Map<String, Object>> weakSections(List<Pt> pts) {
        Map<String, List<Pt>> bySection = pts.stream()
            .filter(p -> p.section() != null && !p.section().isBlank() && p.d0() != null)
            .collect(Collectors.groupingBy(Pt::section));
        List<Map<String, Object>> rows = new ArrayList<>();
        bySection.forEach((section, spts) -> {
            if (spts.size() < WEAK_SECTIONS_MIN_PTS) return;
            double[] v = sortedD0(spts);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", section);
            m.put("district", spts.get(0).district());
            m.put("cls", spts.get(0).cls());
            m.put("points", spts.size());
            m.put("min", r3(v[0]));
            m.put("mean", r3(Arrays.stream(v).average().orElse(0)));
            m.put("max", r3(v[v.length - 1]));
            rows.add(m);
        });
        rows.sort((a, b) -> Double.compare((Double) b.get("mean"), (Double) a.get("mean")));
        return rows.size() > WEAK_SECTIONS_MAX ? rows.subList(0, WEAK_SECTIONS_MAX) : rows;
    }

    private static Map<String, Object> tempStats(List<Pt> pts) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("pavement", oneTemp(pts, Pt::pav));
        t.put("air", oneTemp(pts, Pt::air));
        return t;
    }

    private static Map<String, Object> oneTemp(List<Pt> pts, java.util.function.Function<Pt, Double> get) {
        double[] v = pts.stream().map(get).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
        if (v.length == 0) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", v.length);
        m.put("min", r1(v[0]));
        m.put("max", r1(v[v.length - 1]));
        m.put("mean", r1(Arrays.stream(v).average().orElse(0)));
        return m;
    }

    /* ---- helpers ---- */

    /** ~10–14 equal bins from 0 to just past the period max, on a tidy step. */
    private static double[] binEdges(List<Pt> pts) {
        double max = pts.stream().filter(p -> p.d0 != null).mapToDouble(Pt::d0).max().orElse(1);
        if (max <= 0) max = 1;
        double rawStep = max / 12;
        double mag = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double step = mag;
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (mag * m >= rawStep) { step = mag * m; break; }
        }
        int n = (int) Math.ceil(max / step + 1e-9);
        double[] edges = new double[n + 1];
        for (int i = 0; i <= n; i++) edges[i] = step * i;
        return edges;
    }

    private static int[] histogram(double[] sorted, double[] edges) {
        int bins = edges.length - 1;
        int[] h = new int[bins];
        for (double v : sorted) {
            int b = (int) Math.floor(v / (edges[1] - edges[0]));
            h[Math.max(0, Math.min(bins - 1, b))]++;
        }
        return h;
    }

    /** Even sample of an already-sorted array — keeps the percentile shape. */
    private static double[] decimate(double[] sorted, int max) {
        if (sorted.length <= max) return sorted;
        double[] out = new double[max];
        for (int i = 0; i < max; i++)
            out[i] = sorted[(int) Math.round((double) i * (sorted.length - 1) / (max - 1))];
        return out;
    }

    private static double pct(double[] sorted, double q) {
        double idx = q * (sorted.length - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
    }

    private static double r3(double v) { return Math.round(v * 1000) / 1000.0; }
    private static double r1(double v) { return Math.round(v * 10) / 10.0; }

    private static double[] round3(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = r3(v[i]);
        return out;
    }

    private static String rangeLabel(Map<String, Object> p) {
        Object s = p.get("start_date"), e = p.get("end_date");
        if (s == null && e == null) return "";
        return (s == null ? "…" : s) + " – " + (e == null ? "…" : e);
    }
}
