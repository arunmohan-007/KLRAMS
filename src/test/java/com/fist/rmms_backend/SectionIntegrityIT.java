package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-network invariants for the linear-referenced data. Read-only.
 *
 * <p>Splits and merges rewrite the join key every survey layer hangs off, so the failure they
 * produce is not an exception — it is rows that are present, drawn, and attached to a section
 * that no longer exists, or sitting past the end of the one they name. Nothing in the running
 * app reports either. This asserts both across the entire database, so "is the data sound after
 * all that editing?" has an answer that does not depend on clicking around the map.
 *
 * <p>It also checks that the integration suite left nothing standing: every split or merge those
 * tests apply is undone in a {@code finally}, so an {@code it-*} change still marked applied
 * means a test aborted midway and real sections are sitting in a state nobody asked for.
 *
 * <pre>./mvnw -o test "-Dtest=SectionIntegrityIT"</pre>
 */
@SpringBootTest
class SectionIntegrityIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired SectionLineageService lineage;
    @Autowired RoadColumns roadColumns;

    private String labelColumn() {
        return roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);
    }

    /** The labels inside a change log's {@code result_labels} json array. */
    @SuppressWarnings("unchecked")
    private List<String> labelsOf(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Every dependent row must name a section the road network actually has. */
    @Test
    void noSurveyRowPointsAtASectionThatDoesNotExist() {
        List<String> problems = new ArrayList<>();
        for (SectionLineageService.Target t : lineage.targets()) {
            if (t.table().equals("roads")) continue;
            List<Map<String, Object>> orphans = jdbc.queryForList(
                    "SELECT d.\"" + t.labelColumn() + "\" AS label, count(*) AS rows "
                  + "FROM \"" + t.table() + "\" d "
                  + "WHERE d.\"" + t.labelColumn() + "\" IS NOT NULL "
                  + "  AND NOT EXISTS (SELECT 1 FROM roads r WHERE r." + labelColumn()
                  + "                  = d.\"" + t.labelColumn() + "\") "
                  + "GROUP BY 1 ORDER BY 2 DESC");
            for (Map<String, Object> o : orphans)
                problems.add(t.table() + ": " + o.get("rows") + " row(s) on \"" + o.get("label")
                           + "\", which is not in the road network");
            System.out.println("   " + t.table() + " — orphaned labels: " + orphans.size());
        }
        assertTrue(problems.isEmpty(), "orphaned survey rows:\n  " + String.join("\n  ", problems));
    }

    /**
     * No row may sit past the end of the section it names.
     *
     * <p>A metre of slack: a section's declared length and its drawn length differ slightly, and
     * a survey row ending exactly on the boundary must not read as an error.
     */
    @Test
    void noSurveyRowSitsPastTheEndOfItsSection() {
        /* col("r", …) already qualifies the column with the alias — prefixing "r." again here
           produced r.r."Rd_End_cha" and a bad-grammar error. */
        String len = "COALESCE(NULLIF(" + roadColumns.col("r", LayerAttributeCatalog.ROAD_END_CHAINAGE)
                   + "::double precision - " + roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE)
                   + "::double precision, 0), NULLIF("
                   + roadColumns.col("r", LayerAttributeCatalog.MEASURED_LENGTH) + "::double precision, 0))";
        List<String> problems = new ArrayList<>();
        for (SectionLineageService.Target t : lineage.targets()) {
            if (t.table().equals("roads") || !t.hasChainage()) continue;
            String end = t.isPoint() ? t.startColumn() : t.endColumn();
            List<Map<String, Object>> over = jdbc.queryForList(
                    "SELECT d.\"" + t.labelColumn() + "\" AS label, count(*) AS rows, "
                  + "       round(max(d.\"" + end + "\")::numeric, 1) AS worst, "
                  + "       round(max(" + len + ")::numeric, 1) AS section_len "
                  + "FROM \"" + t.table() + "\" d JOIN roads r ON r." + labelColumn()
                  + "     = d.\"" + t.labelColumn() + "\" "
                  + "WHERE d.\"" + end + "\" > " + len + " + 1.0 GROUP BY 1 ORDER BY 2 DESC");
            for (Map<String, Object> o : over)
                problems.add(t.table() + ": " + o.get("rows") + " row(s) on \"" + o.get("label")
                           + "\" reach " + o.get("worst") + " m, past its " + o.get("section_len") + " m");
            System.out.println("   " + t.table() + " — sections with out-of-range rows: " + over.size());
        }
        assertTrue(problems.isEmpty(), "survey rows past the end of their section:\n  "
                + String.join("\n  ", problems));
    }

    /** A change still marked applied must have produced sections that exist. */
    @Test
    void everyAppliedChangeProducedSectionsThatStillExist() {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, operation, username, result_labels::text AS res FROM section_change_log "
              + "WHERE undone_at IS NULL AND operation <> 'undo' ORDER BY id")) {
            for (String label : labelsOf(String.valueOf(r.get("res")))) {
                Long n = jdbc.queryForObject("SELECT count(*) FROM roads WHERE " + labelColumn() + " = ?",
                        Long.class, label);
                if (n == null || n == 0)
                    problems.add("change " + r.get("id") + " (" + r.get("operation") + " by "
                               + r.get("username") + ") says it produced \"" + label
                               + "\", which is not in the road network");
            }
        }
        assertTrue(problems.isEmpty(), "applied changes whose result is missing:\n  "
                + String.join("\n  ", problems));
    }

    /** The suite undoes everything it applies; anything left standing means a test aborted. */
    @Test
    void theIntegrationSuiteLeftNothingApplied() {
        List<Map<String, Object>> standing = jdbc.queryForList(
                "SELECT id, operation, username, source_labels::text AS src, result_labels::text AS res "
              + "FROM section_change_log WHERE username LIKE 'it-%' "
              + "  AND operation <> 'undo' AND undone_at IS NULL ORDER BY id");
        for (Map<String, Object> r : standing) System.out.println("   STILL APPLIED: " + r);
        System.out.println("   test changes still applied: " + standing.size());
        assertTrue(standing.isEmpty(),
                "the integration suite left " + standing.size() + " change(s) applied to real "
              + "sections: " + standing);
    }
}
