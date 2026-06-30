package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
        "    MAX(\"Current_Ow\") AS current_ow " +
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
