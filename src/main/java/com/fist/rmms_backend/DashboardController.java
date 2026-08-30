package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Road-network dashboard figures.
 *
 * Carriageway correction: a dual road is drawn as two centrelines that each carry
 * the FULL length of one physical stretch, so summing raw Measrd_Len would
 * double-count it. The centrelines belonging to one stretch are named in a
 * carriageway GROUP maintained in the Calculation Rules module, and a group is
 * counted once at the AVERAGE of its members' lengths. An ungrouped section uses
 * its Measrd_Len as-is. (Before that module existed the pairing was guessed from
 * a trailing A/B in Section_La; the groups were seeded from that guess, so the
 * figures did not move when it shipped.)
 *
 * Every breakdown (district, class, PWD section, owner, construction type) groups
 * on the value the road network STORES and reports this corrected length against
 * it. Nothing here rewrites an attribute on the way out: an owner spelled two
 * ways is two rows, which is how the data error gets noticed and fixed at source.
 * The wording a reader sees comes from the Lookup &amp; Short Code list bound to
 * that attribute, applied in the browser (js/11-dashboard-charts.js).
 *
 * /summary also reports each correction's before and after value under
 * "corrections". See {@link CalcRuleService}.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final CalcRuleService rules;

    public DashboardController(JdbcTemplate jdbc, CalcRuleService rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    /* The per-corridor corrected-length view every query below builds on. It is
       a JOIN onto the rule tables, not generated SQL, so there is nothing here
       to invalidate when the rules are edited — the next query simply sees them. */
    private static final String CORR = CalcRuleService.CORR;

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> tot = jdbc.queryForMap(CORR +
            "SELECT COUNT(*) AS corridors, " +
            "       ROUND(SUM(corr_len)::numeric/1000,2) AS km, " +
            "       SUM(CASE WHEN is_dual THEN 1 ELSE 0 END) AS dual_corridors " +
            "FROM corr");
        long rawRoads = jdbc.queryForObject("SELECT COUNT(*) FROM roads", Long.class);
        Double rawKm = jdbc.queryForObject(
            "SELECT ROUND(SUM(\"Measrd_Len\"::double precision)::numeric/1000,2) FROM roads", Double.class);
        Double digKm = null;
        try {
            digKm = jdbc.queryForObject(
                "SELECT ROUND(SUM(\"Dig_L\"::double precision)::numeric/1000,2) FROM roads", Double.class);
        } catch (Exception ignore) { /* Dig_L column may be absent */ }
        out.put("total_km", tot.get("km"));
        out.put("corridors", tot.get("corridors"));
        out.put("dual_corridors", tot.get("dual_corridors"));
        out.put("raw_segments", rawRoads);
        out.put("raw_km", rawKm);
        out.put("dig_km", digKm != null ? digKm : rawKm);

        out.put("by_class",     group("road_class"));
        out.put("by_district",  group("district"));
        out.put("by_pwd_sec",   group("pwd_sec"));
        out.put("by_owner",     group("current_ow"));
        out.put("by_cons_type", group("cons_type"));

        /* Corrected length by construction type per district (flat rows, pivoted
           client-side into the district-wise construction-type matrix). */
        out.put("cons_type_by_district", jdbc.queryForList(CORR +
            "SELECT COALESCE(NULLIF(district,''),'(unspecified)') AS district, " +
            "       COALESCE(NULLIF(cons_type,''),'(unspecified)') AS cons_type, " +
            "       ROUND(SUM(corr_len)::numeric/1000,2) AS km " +
            "FROM corr GROUP BY 1,2 ORDER BY 1,2"));

        out.putAll(shMdrCounts(null));
        out.put("sh_mdr_by_district", shMdrByDistrict());

        /* Every correction applied above, with the figure before it and after it,
           so a reader can see what the rules changed rather than take the totals
           on trust. Each entry also names where else that rule is used. Failing
           to build this must not cost the dashboard its numbers. */
        try {
            out.put("corrections", rules.effects());
        } catch (Exception e) {
            out.put("corrections", List.of());
        }
        return out;
    }

    /* district drill-down: PWD-section and owner lengths within one district */
    @GetMapping("/district")
    public Map<String, Object> district(@RequestParam String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("district", name);
        out.put("total_km", jdbc.queryForObject(CORR +
            "SELECT ROUND(SUM(corr_len)::numeric/1000,2) FROM corr WHERE district = ?",
            Double.class, name));
        out.put("by_class",     groupWhere("road_class", "district", name));
        out.put("by_pwd_sec",   groupWhere("pwd_sec",    "district", name));
        out.put("by_owner",     groupWhere("current_ow", "district", name));
        out.put("by_cons_type", groupWhere("cons_type",  "district", name));
        out.putAll(shMdrCounts(name));
        return out;
    }

    /* Raw road segments for one construction type, so staff can locate (and fix)
       the exact Section labels behind a construction-type bucket — including the
       blank/(unspecified) rows whose Cons_Type was not filled in on import.
       type blank / null / "(unspecified)" -> rows with an empty Cons_Type;
       otherwise rows whose Cons_Type matches. Length is the raw measured length
       per segment (not corrected) because each Section label is edited on its own. */
    @GetMapping("/cons-type-sections")
    public List<Map<String, Object>> consTypeSections(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String district) {
        boolean blank = type == null || type.isBlank() || type.equalsIgnoreCase("(unspecified)");
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT \"Section_La\" AS section_la, \"Road_Name\" AS road_name, " +
            "       \"Road_Class\" AS road_class, \"District\" AS district, " +
            "       \"PWD_Sec\" AS pwd_sec, \"Cons_Type\" AS cons_type, " +
            "       ROUND((\"Measrd_Len\"::double precision/1000)::numeric,3) AS km " +
            "FROM roads WHERE ");
        if (blank) {
            sql.append("NULLIF(trim(\"Cons_Type\"),'') IS NULL");
        } else {
            sql.append("upper(trim(\"Cons_Type\")) = upper(trim(?))");
            args.add(type);
        }
        if (district != null && !district.isBlank()) {
            sql.append(" AND trim(\"District\") = ?");
            args.add(district);
        }
        sql.append(" ORDER BY \"District\", \"Section_La\"");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /* SH count = distinct Road_Num (numbered SH) + distinct Road_Name among SH
       rows that carry no Road_Num (e.g. Section_La = KPWD/SH/<PWD-sec>/<seg> or
       KPWD/SH/Bypass/...) — those unnumbered stretches are grouped by Road_Name
       instead so repeat segments of the same named road count once.
       MDR count = distinct Road_Name among MDR-class rows.
       district == null gives the state-wide figure; otherwise scoped to one district. */
    private Map<String, Object> shMdrCounts(String district) {
        boolean scoped = district != null;
        Object[] args = scoped ? new Object[]{district} : new Object[0];
        String distCond = scoped ? " AND trim(\"District\") = ?" : "";

        Map<String, Object> sh = jdbc.queryForMap(
            "SELECT COUNT(DISTINCT \"Road_Num\") AS numbered, " +
            "       COUNT(DISTINCT CASE WHEN \"Road_Num\" IS NULL " +
            "             THEN NULLIF(trim(\"Road_Name\"),'') END) AS unnumbered " +
            "FROM roads WHERE upper(trim(\"Road_Class\"))='SH'" + distCond, args);
        long numbered = ((Number) sh.get("numbered")).longValue();
        long unnumbered = ((Number) sh.get("unnumbered")).longValue();

        Long mdrCount = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT NULLIF(trim(\"Road_Name\"),'')) FROM roads " +
            "WHERE upper(trim(\"Road_Class\"))='MDR'" + distCond, Long.class, args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sh_numbered_count",   numbered);
        out.put("sh_unnumbered_count", unnumbered);
        out.put("sh_total_count",      numbered + unnumbered);
        out.put("mdr_count",           mdrCount);
        return out;
    }

    /* Per-district breakdown of the same SH/MDR counts, for the district list view. */
    private List<Map<String, Object>> shMdrByDistrict() {
        List<Map<String, Object>> shRows = jdbc.queryForList(
            "SELECT COALESCE(NULLIF(trim(\"District\"),''),'(unspecified)') AS district, " +
            "       COUNT(DISTINCT \"Road_Num\") AS sh_numbered, " +
            "       COUNT(DISTINCT CASE WHEN \"Road_Num\" IS NULL " +
            "             THEN NULLIF(trim(\"Road_Name\"),'') END) AS sh_unnumbered " +
            "FROM roads WHERE upper(trim(\"Road_Class\"))='SH' GROUP BY 1");
        List<Map<String, Object>> mdrRows = jdbc.queryForList(
            "SELECT COALESCE(NULLIF(trim(\"District\"),''),'(unspecified)') AS district, " +
            "       COUNT(DISTINCT NULLIF(trim(\"Road_Name\"),'')) AS mdr_count " +
            "FROM roads WHERE upper(trim(\"Road_Class\"))='MDR' GROUP BY 1");

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : shRows) {
            String d = (String) r.get("district");
            long numbered = ((Number) r.get("sh_numbered")).longValue();
            long unnumbered = ((Number) r.get("sh_unnumbered")).longValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("district", d);
            row.put("sh_numbered_count", numbered);
            row.put("sh_unnumbered_count", unnumbered);
            row.put("sh_total_count", numbered + unnumbered);
            row.put("mdr_count", 0L);
            merged.put(d, row);
        }
        for (Map<String, Object> r : mdrRows) {
            String d = (String) r.get("district");
            Map<String, Object> row = merged.computeIfAbsent(d, k -> {
                Map<String, Object> nr = new LinkedHashMap<>();
                nr.put("district", d);
                nr.put("sh_numbered_count", 0L);
                nr.put("sh_unnumbered_count", 0L);
                nr.put("sh_total_count", 0L);
                nr.put("mdr_count", 0L);
                return nr;
            });
            row.put("mdr_count", r.get("mdr_count"));
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(a -> (String) a.get("district")));
        return out;
    }

    /* Dedicated corrected-length view for the "longest roads" feature.
       Unlike CORR, a corridor is keyed by (district, road_class, road_num,
       road_name, base_label) so every stretch keeps its OWN district — a State
       Highway that runs through several districts is NOT collapsed onto a single
       one. Carriageway groups still share base_label and are averaged once. */
    private static final String LONG_CORR = CalcRuleService.LONG_CORR;

    /* Longest roads (top 10 by corrected length), overall or within one district.
       SH: the same SH number can run under several Road_Names, so lengths are
       summed per Road_Num and every name under that number is listed.
       MDR: summed per Road_Name. */
    @GetMapping("/longest")
    public Map<String, Object> longest(@RequestParam(required = false) String district) {
        // district may be a single name or a comma-separated list (multi-district scope)
        List<String> districts = (district == null || district.isBlank())
            ? Collections.emptyList()
            : Arrays.stream(district.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct()
                .collect(Collectors.toList());
        boolean filtered = !districts.isEmpty();
        String distCond = filtered
            ? " AND trim(district) IN (" + districts.stream().map(d -> "?").collect(Collectors.joining(",")) + ")"
            : "";
        Object[] args = districts.toArray();

        List<Map<String, Object>> sh = jdbc.queryForList(LONG_CORR +
            "SELECT road_num AS num, " +
            "       COALESCE(string_agg(DISTINCT NULLIF(road_name,''), ' · '), '(unnamed)') AS names, " +
            "       string_agg(DISTINCT NULLIF(district,''), ', ') AS districts, " +
            "       COUNT(*) AS sections, " +
            "       ROUND(SUM(corr_len)::numeric/1000,2) AS km " +
            "FROM corr WHERE upper(trim(road_class))='SH'" + distCond +
            " GROUP BY road_num ORDER BY km DESC NULLS LAST LIMIT 10", args);

        List<Map<String, Object>> mdr = jdbc.queryForList(LONG_CORR +
            "SELECT COALESCE(NULLIF(road_name,''),'(unnamed)') AS names, " +
            "       string_agg(DISTINCT NULLIF(district,''), ', ') AS districts, " +
            "       COUNT(*) AS sections, " +
            "       ROUND(SUM(corr_len)::numeric/1000,2) AS km " +
            "FROM corr WHERE upper(trim(road_class))='MDR'" + distCond +
            " GROUP BY 1 ORDER BY km DESC NULLS LAST LIMIT 10", args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("district", filtered ? String.join(", ", districts) : null);
        out.put("districts", districts);
        out.put("sh", sh);
        out.put("mdr", mdr);
        return out;
    }

    private List<Map<String, Object>> group(String col) {
        return jdbc.queryForList(CORR +
            "SELECT COALESCE(NULLIF(" + col + ",''),'(unspecified)') AS label, " +
            "       COUNT(*) AS roads, ROUND(SUM(corr_len)::numeric/1000,2) AS km " +
            "FROM corr GROUP BY 1 ORDER BY km DESC NULLS LAST");
    }

    private List<Map<String, Object>> groupWhere(String col, String whereCol, String val) {
        return jdbc.queryForList(CORR +
            "SELECT COALESCE(NULLIF(" + col + ",''),'(unspecified)') AS label, " +
            "       COUNT(*) AS roads, ROUND(SUM(corr_len)::numeric/1000,2) AS km " +
            "FROM corr WHERE " + whereCol + " = ? GROUP BY 1 ORDER BY km DESC NULLS LAST", val);
    }
}
