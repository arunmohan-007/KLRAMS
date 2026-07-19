package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Road-network dashboard figures.
 *
 * Dual-carriageway handling: a dual road is drawn as two centrelines whose
 * Section_La differ only by a trailing A/B (e.g. .../1A and .../1B) and whose
 * Single_Du = 'Dual'. Summing raw Measrd_Len would double-count them, so for a
 * dual pair we count the AVERAGE of the two lengths once (grouped by the label
 * with the trailing A/B stripped). Single roads use Measrd_Len as-is.
 *
 * Every breakdown (district, class, PWD section, owner) uses this corrected length.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* A per-corridor corrected-length view, built once and reused by every query.
       base_label = Section_La with a trailing A or B removed when Single_Du='Dual'.
       corrected length per corridor:
         - dual  -> AVG(Measrd_Len) over the A/B rows that share base_label
         - single-> the row's Measrd_Len
       Attributes (district/class/section/owner) are taken as the MAX (any) within
       the corridor — both halves carry the same values. */
    private static final String CORR =
        "WITH base AS (" +
        "  SELECT *, " +
        "    CASE WHEN lower(\"Single_Du\")='dual' AND \"Section_La\" ~ '[AB]$' " +
        "         THEN left(\"Section_La\", length(\"Section_La\")-1) " +
        "         ELSE \"Section_La\" END AS base_label, " +
        "    lower(\"Single_Du\")='dual' AS is_dual " +
        "  FROM roads), " +
        "corr AS (" +
        "  SELECT base_label, " +
        "    bool_or(is_dual) AS is_dual, " +
        "    CASE WHEN bool_or(is_dual) THEN AVG(\"Measrd_Len\"::double precision) " +
        "         ELSE MAX(\"Measrd_Len\"::double precision) END AS corr_len, " +
        "    MAX(\"District\")   AS district, " +
        "    MAX(\"Road_Class\") AS road_class, " +
        "    MAX(\"PWD_Sec\")    AS pwd_sec, " +
        /* Owner names are entered inconsistently (e.g. "KRFB — PMU" vs "KRFB PMU"):
           collapse any run of dashes/em-dashes to a single space and squeeze
           repeated whitespace so both variants group together downstream. */
        "    MAX(regexp_replace(regexp_replace(trim(\"Current_Ow\"), '[-–—]+', ' ', 'g'), '\\s+', ' ', 'g')) AS current_ow " +
        "  FROM base GROUP BY base_label) ";

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

        out.put("by_class",    group("road_class"));
        out.put("by_district", group("district"));
        out.put("by_pwd_sec",  group("pwd_sec"));
        out.put("by_owner",    group("current_ow"));

        out.putAll(shMdrCounts(null));
        out.put("sh_mdr_by_district", shMdrByDistrict());
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
        out.put("by_class",   groupWhere("road_class", "district", name));
        out.put("by_pwd_sec", groupWhere("pwd_sec",    "district", name));
        out.put("by_owner",   groupWhere("current_ow", "district", name));
        out.putAll(shMdrCounts(name));
        return out;
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
       one. Dual A/B carriageways still share base_label and are averaged once. */
    private static final String LONG_CORR =
        "WITH base AS (" +
        "  SELECT \"District\" AS district, \"Road_Class\" AS road_class, " +
        "         \"Road_Num\" AS road_num, \"Road_Name\" AS road_name, " +
        "         \"Measrd_Len\"::double precision AS len, " +
        "         lower(\"Single_Du\")='dual' AS is_dual, " +
        "         CASE WHEN lower(\"Single_Du\")='dual' AND \"Section_La\" ~ '[AB]$' " +
        "              THEN left(\"Section_La\", length(\"Section_La\")-1) " +
        "              ELSE \"Section_La\" END AS base_label " +
        "  FROM roads), " +
        "corr AS (" +
        "  SELECT district, road_class, road_num, road_name, " +
        "    CASE WHEN bool_or(is_dual) THEN AVG(len) ELSE MAX(len) END AS corr_len " +
        "  FROM base GROUP BY district, road_class, road_num, road_name, base_label) ";

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
