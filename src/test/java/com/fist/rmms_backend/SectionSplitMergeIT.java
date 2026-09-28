package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Splits and merges real sections in the database, checks the result, and puts everything back.
 *
 * <p>These are the writes the whole module exists for, and the failure they have to exclude is
 * not an exception — it is survey rows that are present, drawn, and proportionally in the wrong
 * place. Nothing in the system detects that, so it is checked here directly: the total length
 * covered by each layer's rows on a section is measured before the change and again after, and
 * must be identical. A chainage that was re-based by the wrong amount changes that total.
 *
 * <p>Every test restores what it changed in a {@code finally}, so the development database is
 * left as it was found even when an assertion fails partway through.
 */
@SpringBootTest
class SectionSplitMergeIT {

    @Autowired SectionLineageService lineage;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;

    private String labelColumn() {
        return roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);
    }

    /** Every (start, end) span a table holds for one section label, sorted. */
    private List<double[]> spans(String table, String labelCol, String startCol, String endCol, String label) {
        List<double[]> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT \"" + startCol + "\" AS s, \"" + endCol + "\" AS e FROM \"" + table + "\" "
              + "WHERE \"" + labelCol + "\" = ? ORDER BY 1, 2", label)) {
            if (r.get("s") == null) continue;
            double start = ((Number) r.get("s")).doubleValue();
            /* A point asset has no end chainage. Treating the null as 0 would make its "length"
               equal to its start chainage — a figure that changes the moment the row is shifted,
               so the coverage check would report a difference for rows that moved correctly. */
            double end = r.get("e") == null ? start : ((Number) r.get("e")).doubleValue();
            out.add(new double[]{start, end});
        }
        return out;
    }

    /** Total metres covered by a table's rows on a section — the invariant a bad shift breaks. */
    private double covered(String table, String labelCol, String startCol, String endCol, String label) {
        double sum = 0;
        for (double[] s : spans(table, labelCol, startCol, endCol, label)) sum += Math.abs(s[1] - s[0]);
        return sum;
    }

    private long rows(String table, String labelCol, String label) {
        return jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" WHERE \"" + labelCol + "\" = ?",
                Long.class, label);
    }

    /** A section carrying condition rows, with a usable chainage range and a mergeable line. */
    private String splittableSectionWithData() {
        List<String> found = jdbc.queryForList(
                "SELECT r." + labelColumn() + " FROM roads r "
              + "WHERE r.geom IS NOT NULL "
              + "  AND ST_GeometryType(ST_LineMerge(r.geom)) = 'ST_LineString' "
              + "  AND " + roadColumns.col("r", LayerAttributeCatalog.ROAD_END_CHAINAGE) + "::double precision "
              + "    - " + roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE) + "::double precision > 500 "
              + "  AND abs(abs(" + roadColumns.col("r", LayerAttributeCatalog.ROAD_END_CHAINAGE) + "::double precision "
              + "           - " + roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE) + "::double precision) "
              + "        - " + roadColumns.col("r", LayerAttributeCatalog.MEASURED_LENGTH) + "::double precision) <= 0.5 "
              + "  AND EXISTS (SELECT 1 FROM condition d WHERE d.section_label = r." + labelColumn() + ") "
              + "ORDER BY 1 LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /* ================= split, then undo ================= */

    @Test
    void aSplitMovesEveryRowOntoTheRightHalfAndUndoPutsItBack() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section carrying condition data");

        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        double cutLocal = cutAbs - before.startCh();
        String first = parent + "/X1", second = parent + "/X2";

        long condBefore = rows("condition", "section_label", parent);
        long assetsBefore = rows("road_assets", "section_label", parent);
        double condCoveredBefore = covered("condition", "section_label", "start_chainage", "end_chainage", parent);
        double assetsCoveredBefore = covered("road_assets", "section_label", "start_chainage", "end_chainage", parent);

        System.out.printf("[split] %s  len %.1f  cut at local %.1f  condition %d rows / %.1f m%n",
                parent, Math.abs(before.refLen()), cutLocal, condBefore, condCoveredBefore);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true, Map.of(), Map.of(), "it-split");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();
            System.out.println("[split] " + r.get("moved"));

            /* --- the road network --- */
            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), parent), "parent row must be gone");
            SectionLineage.Section a = lineage.loadSection(first);
            SectionLineage.Section b = lineage.loadSection(second);
            assertNotNull(a, "first half missing"); assertNotNull(b, "second half missing");
            assertEquals(before.startCh(), a.startCh(), 1e-6, "first half keeps the parent's start");
            assertEquals(cutAbs, a.endCh(), 1e-6, "first half ends at the cut");
            assertEquals(cutAbs, b.startCh(), 1e-6, "second half starts at the cut");
            assertEquals(before.endCh(), b.endCh(), 1e-6, "second half keeps the parent's end");
            assertEquals(0, b.localStartCh(), "the second half restarts its own chainage at 0");
            assertEquals(Math.abs(before.refLen()), Math.abs(a.refLen()) + Math.abs(b.refLen()), 1e-6,
                    "the halves must still total the parent's length");

            // Geometry was actually cut, and the two halves still add up.
            Double lenA = jdbc.queryForObject("SELECT ST_Length(geom::geography) FROM roads WHERE "
                    + labelColumn() + " = ?", Double.class, first);
            Double lenB = jdbc.queryForObject("SELECT ST_Length(geom::geography) FROM roads WHERE "
                    + labelColumn() + " = ?", Double.class, second);
            assertNotNull(lenA); assertNotNull(lenB);
            assertEquals(before.geomLen(), lenA + lenB, Math.max(1.0, before.geomLen() * 0.001),
                    "the cut centrelines must still total the parent's drawn length");

            /* --- the survey rows: the invariant that catches a bad shift --- */
            double condAfter =
                    covered("condition", "section_label", "start_chainage", "end_chainage", first)
                  + covered("condition", "section_label", "start_chainage", "end_chainage", second);
            assertEquals(condCoveredBefore, condAfter, 0.01,
                    "condition rows cover a different length after the split — a chainage was "
                  + "re-based by the wrong amount");

            double assetsAfter =
                    covered("road_assets", "section_label", "start_chainage", "end_chainage", first)
                  + covered("road_assets", "section_label", "start_chainage", "end_chainage", second);
            assertEquals(assetsCoveredBefore, assetsAfter, 0.01, "road assets cover a different length");

            assertEquals(assetsBefore,
                    rows("road_assets", "section_label", first) + rows("road_assets", "section_label", second),
                    "an asset was lost or duplicated — discrete objects must move whole");

            // No row may sit past its own section's length.
            assertBounded("condition", first, Math.abs(a.refLen()));
            assertBounded("condition", second, Math.abs(b.refLen()));

            System.out.printf("[split] condition now %d + %d rows, %.1f m covered (was %.1f)%n",
                    rows("condition", "section_label", first), rows("condition", "section_label", second),
                    condAfter, condCoveredBefore);

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                System.out.println("[undo] " + u.get("message"));

                /* Checked here, before the cleanup below re-joins them: an undo deliberately
                   leaves a divided row as two adjacent rows covering the original span. */
                long afterUndo = rows("condition", "section_label", parent);
                assertTrue(afterUndo >= condBefore, "condition rows were lost in the undo");
                System.out.printf("[undo] condition %d rows (was %d; a divided row stays two)%n",
                        afterUndo, condBefore);

                rejoinDividedRows(parent, cutLocal);
            }
        }

        /* --- after the undo --- */
        SectionLineage.Section restored = lineage.loadSection(parent);
        assertNotNull(restored, "the parent section was not restored");
        assertEquals(before.startCh(), restored.startCh(), 1e-6, "restored start chainage");
        assertEquals(before.endCh(), restored.endCh(), 1e-6, "restored end chainage");
        assertEquals(0, rows("roads", labelColumn().replace("\"", ""), first), "first half must be gone");
        assertEquals(0, rows("roads", labelColumn().replace("\"", ""), second), "second half must be gone");

        assertEquals(condCoveredBefore,
                covered("condition", "section_label", "start_chainage", "end_chainage", parent), 0.01,
                "condition rows cover a different length after the undo");
        assertEquals(assetsBefore, rows("road_assets", "section_label", parent),
                "road assets were not all returned");
        assertEquals(assetsCoveredBefore,
                covered("road_assets", "section_label", "start_chainage", "end_chainage", parent), 0.01,
                "road assets cover a different length after the undo");

        // After the cleanup the section is byte-for-byte where it started.
        assertEquals(condBefore, rows("condition", "section_label", parent),
                "the section was not left exactly as it was found");
    }

    /**
     * Re-joins the condition rows this test's split divided, so running the suite twice does not
     * leave the database a row further from where it started each time.
     *
     * <p>An undo deliberately leaves a divided row as two adjacent rows — same coverage, one
     * extra row — because re-joining them means choosing which of two equal measurements
     * survives, and that is not a decision the code should make silently. Here the answer is
     * known: this test created the division a moment ago, so it can undo it precisely.
     */
    private void rejoinDividedRows(String label, double cut) {
        int rejoined = 0;
        /* Matching "adjacent at the cut" alone is NOT enough, and getting that wrong destroyed
           two genuine survey rows: 3900-4000 and 4000-4100 on a real section are adjacent at
           4000 and were merged into one 200 m row, discarding the second's measurements.

           A row the split DIVIDED is identifiable: both halves carry identical measurements (a
           division copies them), and the half on the far side is an INSERT, so its id is newer.
           Requiring both leaves genuine neighbours — which differ in at least one measurement —
           untouched. */
        for (Map<String, Object> pair : jdbc.queryForList(
                "SELECT lo.id AS lo_id, hi.id AS hi_id, hi.end_chainage AS hi_end "
              + "FROM condition lo JOIN condition hi "
              + "  ON hi.section_label = lo.section_label "
              + " AND hi.start_chainage = lo.end_chainage "
              + " AND hi.xsp IS NOT DISTINCT FROM lo.xsp "
              + " AND hi.period_id IS NOT DISTINCT FROM lo.period_id "
              + " AND hi.id > lo.id "
              + " AND hi.iri        IS NOT DISTINCT FROM lo.iri "
              + " AND hi.crack      IS NOT DISTINCT FROM lo.crack "
              + " AND hi.pothole    IS NOT DISTINCT FROM lo.pothole "
              + " AND hi.rutting    IS NOT DISTINCT FROM lo.rutting "
              + " AND hi.texture    IS NOT DISTINCT FROM lo.texture "
              + " AND hi.patch_work IS NOT DISTINCT FROM lo.patch_work "
              + " AND hi.ravelling  IS NOT DISTINCT FROM lo.ravelling "
              + "WHERE lo.section_label = ? AND lo.end_chainage = ?", label, cut)) {
            jdbc.update("UPDATE condition SET end_chainage = ? WHERE id = ?",
                    pair.get("hi_end"), pair.get("lo_id"));
            jdbc.update("DELETE FROM condition WHERE id = ?", pair.get("hi_id"));
            rejoined++;
        }
        if (rejoined > 0) System.out.println("[cleanup] re-joined " + rejoined + " divided condition row(s)");
    }

    private void assertBounded(String table, String label, double length) {
        Long beyond = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + table + "\" WHERE section_label = ? AND end_chainage > ?",
                Long.class, label, length + 0.5);
        assertEquals(0L, beyond, table + " has row(s) past the end of \"" + label + "\" (" + length + " m)");
    }

    /* ================= merge, then undo ================= */

    @Test
    void aMergeShiftsTheFollowingSectionAndUndoPutsItBack() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no adjacent same-road pair in this database");
        String a = pair.get(0), b = pair.get(1);

        SectionLineage.Section sa = lineage.loadSection(a), sb = lineage.loadSection(b);
        double lenA = Math.abs(sa.refLen());
        double coveredA = covered("condition", "section_label", "start_chainage", "end_chainage", a);
        double coveredB = covered("condition", "section_label", "start_chainage", "end_chainage", b);
        long rowsA = rows("condition", "section_label", a), rowsB = rows("condition", "section_label", b);

        System.out.printf("[merge] %s (%.1f m, %d rows) + %s (%d rows)%n", a, lenA, rowsA, b, rowsB);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applyMerge(List.of(a, b), a, true, true, Map.of(), "it-merge");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();
            System.out.println("[merge] " + r.get("moved"));

            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), b), "the second row must be gone");
            SectionLineage.Section merged = lineage.loadSection(a);
            assertNotNull(merged);
            assertEquals(Math.abs(sa.refLen()) + Math.abs(sb.refLen()), Math.abs(merged.refLen()), 1e-6,
                    "the merged length must be the sum of the parts");

            assertEquals(coveredA + coveredB,
                    covered("condition", "section_label", "start_chainage", "end_chainage", a), 0.01,
                    "merged condition rows cover a different length — an offset was wrong");
            assertEquals(rowsA + rowsB, rows("condition", "section_label", a), "condition rows were lost");

            // The follower's rows must now start at or beyond the leader's length.
            Long early = jdbc.queryForObject(
                    "SELECT count(*) FROM condition WHERE section_label = ? AND start_chainage >= ?",
                    Long.class, a, lenA);
            assertEquals(rowsB, early, "the second section's rows were not shifted past the first's length");

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                System.out.println("[undo] " + u.get("message"));
            }
        }

        assertNotNull(lineage.loadSection(a), "section A was not restored");
        assertNotNull(lineage.loadSection(b), "section B was not restored");
        assertEquals(rowsA, rows("condition", "section_label", a), "A's condition rows were not returned");
        assertEquals(rowsB, rows("condition", "section_label", b), "B's condition rows were not returned");
        assertEquals(coveredA, covered("condition", "section_label", "start_chainage", "end_chainage", a), 0.01,
                "A covers a different length after the undo");
        assertEquals(coveredB, covered("condition", "section_label", "start_chainage", "end_chainage", b), 0.01,
                "B covers a different length after the undo");
    }

    private List<String> adjacentPairOnOneRoad() {
        String lbl = labelColumn();
        String rs = roadColumns.col("a", LayerAttributeCatalog.ROAD_START_CHAINAGE);
        String re = roadColumns.col("a", LayerAttributeCatalog.ROAD_END_CHAINAGE);
        String rs2 = roadColumns.col("b", LayerAttributeCatalog.ROAD_START_CHAINAGE);
        String re2 = roadColumns.col("b", LayerAttributeCatalog.ROAD_END_CHAINAGE);
        String ml = roadColumns.col("a", LayerAttributeCatalog.MEASURED_LENGTH);
        String ml2 = roadColumns.col("b", LayerAttributeCatalog.MEASURED_LENGTH);
        String name = roadColumns.col("a", LayerAttributeCatalog.ROAD_NAME);
        String name2 = roadColumns.col("b", LayerAttributeCatalog.ROAD_NAME);
        List<Map<String, Object>> f = jdbc.queryForList(
                "SELECT a." + lbl + " AS first, b." + lbl + " AS second FROM roads a JOIN roads b "
              + "  ON abs(" + rs2 + "::double precision - " + re + "::double precision) <= 1.0 "
              + " AND TRIM(" + name + ") = TRIM(" + name2 + ") AND a." + lbl + " <> b." + lbl + " "
              + "WHERE a.geom IS NOT NULL AND b.geom IS NOT NULL "
              + "  AND ST_GeometryType(ST_LineMerge(a.geom)) = 'ST_LineString' "
              + "  AND ST_GeometryType(ST_LineMerge(b.geom)) = 'ST_LineString' "
              + "  AND " + re + "::double precision > " + rs + "::double precision "
              + "  AND " + re2 + "::double precision > " + rs2 + "::double precision "
              + "  AND abs(abs(" + re + "::double precision - " + rs + "::double precision) - "
              + ml + "::double precision) <= 0.5 "
              + "  AND abs(abs(" + re2 + "::double precision - " + rs2 + "::double precision) - "
              + ml2 + "::double precision) <= 0.5 "
              + "  AND EXISTS (SELECT 1 FROM condition d WHERE d.section_label = b." + lbl + ") "
              /* Abutting chainage does not mean touching geometry — in this network most
                 chainage-adjacent pairs are drawn kilometres apart, and the merge rightly
                 refuses those. Only a pair whose lines actually join can be merged. */
              + "  AND ST_GeometryType(ST_LineMerge(ST_Union(a.geom, b.geom))) = 'ST_LineString' "
              + "ORDER BY 1 LIMIT 1");
        if (f.isEmpty()) return null;
        return List.of(String.valueOf(f.get(0).get("first")), String.valueOf(f.get(0).get("second")));
    }

    /* ================= the preview's per-half counts ================= */

    /**
     * The preview must predict, per layer, exactly how many rows each half receives — and the
     * commit must then produce those numbers.
     *
     * <p>This is the check that would have caught the second-half column reading as empty: the
     * preview reported only a total and a straddler count, so "how much moves to the new
     * section" was never actually computed, and the screen filled the gap with a dash.
     */
    @Test
    void thePreviewPredictsEachHalfsRowCountAndTheCommitMatchesIt() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section carrying data");
        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        String first = parent + "/P1", second = parent + "/P2";

        Map<String, Object> preview = lineage.previewSplit(parent, cutAbs, first, second, "it");
        assertEquals("ok", preview.get("status"), String.valueOf(preview.get("message")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) preview.get("tables");
        assertFalse(tables.isEmpty(), "this section carries rows, so the preview must list them");

        Map<String, Long> predictedFirst = new LinkedHashMap<>();
        Map<String, Long> predictedSecond = new LinkedHashMap<>();
        for (Map<String, Object> t : tables) {
            String table = String.valueOf(t.get("table"));
            assertNotNull(t.get("to_first"), table + ": the preview must say how many go to the first half");
            assertNotNull(t.get("to_second"), table + ": the preview must say how many go to the second half");
            predictedFirst.put(table, ((Number) t.get("to_first")).longValue());
            predictedSecond.put(table, ((Number) t.get("to_second")).longValue());
            System.out.println("[halves] " + table + ": rows=" + t.get("rows")
                    + " first=" + t.get("to_first") + " second=" + t.get("to_second")
                    + (t.get("divided") != null ? " divided=" + t.get("divided") : ""));
        }
        // A split that sends nothing to the new section is not a split anyone wants to make.
        assertTrue(predictedSecond.values().stream().anyMatch(n -> n > 0),
                "the preview predicts no rows at all on the second half: " + predictedSecond);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true,
                    Map.of(), Map.of(), "it-halves");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            for (SectionLineageService.Target t : lineage.targets()) {
                if (t.table().equals("roads")) continue;
                if (!predictedFirst.containsKey(t.table())) continue;
                assertEquals(predictedFirst.get(t.table()).longValue(),
                        rows(t.table(), t.labelColumn(), first),
                        t.table() + ": the first half did not receive the predicted row count");
                assertEquals(predictedSecond.get(t.table()).longValue(),
                        rows(t.table(), t.labelColumn(), second),
                        t.table() + ": the second half did not receive the predicted row count");
            }
            System.out.println("[halves] commit matched the preview for every layer");

        } finally {
            if (changeId != null) {
                lineage.undo(changeId, true, "it-halves-undo");
                rejoinDividedRows(parent, cutAbs - before.startCh());
            }
        }
        assertNotNull(lineage.loadSection(parent), "the parent was not restored");
    }

    /**
     * The merge preview must account for EVERY section being merged, in every layer it reports —
     * including a section that happens to hold no rows in that layer.
     *
     * <p>Omitting the empty ones reads as "this section's data is missing", which is the same
     * misreading the split's blank second-half column produced. "0" and "absent" are different
     * statements, and only one of them is true.
     */
    @Test
    void theMergePreviewAccountsForEverySectionInEveryLayer() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no adjacent same-road pair");

        Map<String, Object> preview = lineage.previewMerge(pair, pair.get(0), "it");
        assertEquals("ok", preview.get("status"), String.valueOf(preview.get("message")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) preview.get("tables");
        assertFalse(tables.isEmpty(), "these sections carry rows, so the preview must list them");

        for (Map<String, Object> t : tables) {
            @SuppressWarnings("unchecked")
            Map<String, Object> by = (Map<String, Object>) t.get("by_section");
            assertNotNull(by, t.get("table") + ": no per-section breakdown");
            System.out.println("[merge-halves] " + t.get("table") + ": rows=" + t.get("rows") + " " + by);
            for (String label : pair) {
                assertTrue(by.containsKey(label),
                        t.get("table") + ": \"" + label + "\" is missing from the breakdown — a "
                      + "section with no rows in a layer must report 0, not be left out");
            }
            long summed = by.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
            assertEquals(((Number) t.get("rows")).longValue(), summed,
                    t.get("table") + ": the per-section counts do not add up to the total");
        }
    }

    /* ================= editable attributes ================= */

    /**
     * The attributes an operator types on the resulting sections must actually be written — and
     * each half must be able to differ, which is the whole point of offering two columns.
     */
    @Test
    void attributeEditsAreWrittenToEachHalfIndependently() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section");
        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        String first = parent + "/A1", second = parent + "/A2";

        Map<String, Object> info = lineage.sectionInfo(parent);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) info.get("attributes");
        assertNotNull(attrs, "sectionInfo must offer editable attributes");
        assertFalse(attrs.isEmpty(), "the road network carries attributes to edit");

        // The label and the four chainage columns are computed, never offered.
        List<String> offered = attrs.stream().map(a -> String.valueOf(a.get("column"))).toList();
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        for (String computed : new String[]{c.label(), c.roadStart(), c.roadEnd(),
                                            c.sectionStart(), c.sectionEnd(), c.measured()}) {
            if (computed != null)
                assertFalse(offered.contains(computed),
                        computed + " is computed by the split and must not be editable here");
        }
        String column = offered.get(0);
        System.out.println("[attrs] editing \"" + column + "\" of " + offered.size() + " offered");

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true,
                    Map.of(column, "IT-FIRST"), Map.of(column, "IT-SECOND"), "it-attrs");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            String lbl = labelColumn();
            assertEquals("IT-FIRST", jdbc.queryForObject(
                    "SELECT \"" + column + "\"::text FROM roads WHERE " + lbl + " = ?", String.class, first),
                    "the first half did not take the value typed for it");
            assertEquals("IT-SECOND", jdbc.queryForObject(
                    "SELECT \"" + column + "\"::text FROM roads WHERE " + lbl + " = ?", String.class, second),
                    "the second half did not take its own value");
            System.out.println("[attrs] both halves kept their own value");

        } finally {
            if (changeId != null) {
                lineage.undo(changeId, true, "it-attrs-undo");
                rejoinDividedRows(parent, cutAbs - before.startCh());
            }
        }
        assertNotNull(lineage.loadSection(parent), "the parent was not restored");
    }

    @Test
    void aComputedColumnCannotBeSetThroughAttributeOverrides() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section");
        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        String chainageColumn = lineage.roadColumns().roadEnd();

        Exception e = assertThrows(Exception.class, () -> lineage.applySplit(
                parent, cutAbs, parent + "/Z1", parent + "/Z2", true, true,
                Map.of(chainageColumn, "999999"), Map.of(), "it-bad"));
        System.out.println("[attrs] refused: " + e.getMessage());
        assertNotNull(lineage.loadSection(parent), "a refused apply must change nothing");
    }

    /* ================= the guards ================= */

    @Test
    void applyRefusesWithoutConfirm() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no suitable section");
        SectionLineage.Section s = lineage.loadSection(parent);
        double cut = s.startCh() + (s.endCh() - s.startCh()) / 2;

        Map<String, Object> r = lineage.applySplit(parent, cut, parent + "/A", parent + "/B",
                false, true, Map.of(), Map.of(), "it");
        assertEquals("refused", r.get("status"));
        assertNotNull(lineage.loadSection(parent), "a refused split must change nothing");
    }

    /* ================= reusing a source's own label as the result ================= */

    /**
     * Merging A and B into "B" must produce a section named B, not delete it.
     *
     * <p>Keeping one of the merged sections' labels is the ordinary thing to do and the editor
     * offers it — but the merge keeps the FIRST source's row and deletes the rest, so choosing
     * any label but the first one's put the surviving row on the delete list. The merge reported
     * success, the history showed it, and the section was gone from the map with every condition
     * row still pointing at it. It happened to KPWD/SH/1/2/2 + KPWD/SH/1/3 → KPWD/SH/1/3.
     *
     * <p>The second half of the same fault is arithmetic: the sources are re-based in order, so
     * the one sharing the result's label was processed last and shifted every earlier section's
     * rows a second time.
     */
    @Test
    void aMergeMayKeepTheSecondSectionsLabelWithoutDeletingIt() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no adjacent same-road pair in this database");
        String a = pair.get(0), b = pair.get(1);

        SectionLineage.Section sa = lineage.loadSection(a), sb = lineage.loadSection(b);
        double lenA = Math.abs(sa.refLen()), lenB = Math.abs(sb.refLen());
        double coveredA = covered("condition", "section_label", "start_chainage", "end_chainage", a);
        double coveredB = covered("condition", "section_label", "start_chainage", "end_chainage", b);
        long rowsA = rows("condition", "section_label", a), rowsB = rows("condition", "section_label", b);

        System.out.printf("[reuse] %s (%.1f m) + %s (%.1f m) -> \"%s\" (the SECOND label)%n",
                a, lenA, b, lenB, b);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applyMerge(List.of(a, b), b, true, true, Map.of(), "it-reuse");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            assertEquals(1, rows("roads", labelColumn().replace("\"", ""), b),
                    "the merged section must exist exactly once under the label it was given");
            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), a),
                    "the consumed section must be gone");

            SectionLineage.Section merged = lineage.loadSection(b);
            assertNotNull(merged, "the merged section was deleted by its own merge");
            assertEquals(lenA + lenB, Math.abs(merged.refLen()), 1e-6,
                    "the merged length must be the sum of the parts");

            assertEquals(rowsA + rowsB, rows("condition", "section_label", b), "condition rows were lost");
            assertEquals(coveredA + coveredB,
                    covered("condition", "section_label", "start_chainage", "end_chainage", b), 0.01,
                    "merged condition rows cover a different length — an offset was wrong");

            /* The double-shift check: exactly B's rows may sit past A's length. If the sources
               were re-based in the wrong order, A's rows were shifted twice and land there too. */
            Long past = jdbc.queryForObject(
                    "SELECT count(*) FROM condition WHERE section_label = ? AND start_chainage >= ?",
                    Long.class, b, lenA);
            assertEquals(rowsB, past,
                    "rows from the first section were shifted a second time — they now sit inside "
                  + "the second section's range");
            assertBounded("condition", b, lenA + lenB);

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-reuse-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
            }
        }

        assertNotNull(lineage.loadSection(a), "section A was not restored");
        assertNotNull(lineage.loadSection(b), "section B was not restored");
        assertEquals(rowsA, rows("condition", "section_label", a), "A's condition rows were not returned");
        assertEquals(rowsB, rows("condition", "section_label", b), "B's condition rows were not returned");
        assertEquals(coveredA, covered("condition", "section_label", "start_chainage", "end_chainage", a), 0.01,
                "A covers a different length after the undo");
        assertEquals(coveredB, covered("condition", "section_label", "start_chainage", "end_chainage", b), 0.01,
                "B covers a different length after the undo");
    }

    /**
     * A merged section ends where its LAST source ended, and must say so.
     *
     * <p>The merge keeps the first source's road row, which is what carries every unrelated
     * attribute across intact — but that row's Road End Location names the join between the two
     * sections, not the end of the merged one. A section running Thycad → Karette → Nedumangad
     * reported that it ends at Karette, and both the viewer's route label and the NSV route card
     * repeated it, so the map disagreed with the chainage sitting beside it.
     */
    @Test
    void aMergedSectionTakesTheLastSourcesEndLocation() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no adjacent same-road pair in this database");
        String a = pair.get(0), b = pair.get(1);

        String endCol = roadColumns.col(LayerAttributeCatalog.ROAD_END_LOCATION);
        assumeTrue(endCol != null, "this road network carries no Road End Location");
        String rawEndCol = endCol.replace("\"", "");

        String endOfA = endLoc(rawEndCol, a), endOfB = endLoc(rawEndCol, b);
        assumeTrue(endOfB != null && !endOfB.isBlank(), "the second section has no end location to carry");
        assumeTrue(!endOfB.equals(endOfA), "both sections name the same end location — nothing to tell apart");

        System.out.printf("[endloc] %s ends at \"%s\", %s ends at \"%s\"%n", a, endOfA, b, endOfB);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applyMerge(List.of(a, b), a, true, true, Map.of(), "it-endloc");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            assertEquals(endOfB, endLoc(rawEndCol, a),
                    "the merged section still claims it ends where the first source did — that is "
                  + "the join, not the end of the merged section");
        } finally {
            if (changeId != null)
                assertEquals("ok", lineage.undo(changeId, true, "it-endloc-undo").get("status"));
        }

        assertEquals(endOfA, endLoc(rawEndCol, a), "A's end location was not restored by the undo");
        assertEquals(endOfB, endLoc(rawEndCol, b), "B's end location was not restored by the undo");
    }

    private String endLoc(String col, String label) {
        List<String> v = jdbc.queryForList(
                "SELECT \"" + col + "\" FROM roads WHERE " + labelColumn() + " = ?", String.class, label);
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * The mirror of the merge fault: a split whose SECOND half keeps the parent's label.
     *
     * <p>The second child is inserted before the parent row is rewritten as the first child, so
     * the rewrite — matched on the parent's label — caught the row just inserted as well, and
     * both rows came out as the first half. On the dependent tables the same collision sent the
     * whole second half back into the first.
     */
    @Test
    void aSplitMayGiveTheSecondHalfTheParentsLabel() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section carrying condition data");

        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        double cutLocal = cutAbs - before.startCh();
        String first = parent + "/Z1", second = parent;          // the second half keeps the name

        long condBefore = rows("condition", "section_label", parent);
        double condCoveredBefore = covered("condition", "section_label", "start_chainage", "end_chainage", parent);

        System.out.printf("[reuse] split %s at local %.1f, second half keeps the label%n", parent, cutLocal);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true,
                    Map.of(), Map.of(), "it-reuse-split");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            assertEquals(1, rows("roads", labelColumn().replace("\"", ""), first), "one first half");
            assertEquals(1, rows("roads", labelColumn().replace("\"", ""), second),
                    "the second half must exist exactly once under the parent's label");

            SectionLineage.Section x = lineage.loadSection(first), y = lineage.loadSection(second);
            assertNotNull(x); assertNotNull(y);
            assertEquals(cutAbs, x.endCh(), 1e-6, "first half ends at the cut");
            assertEquals(cutAbs, y.startCh(), 1e-6, "second half starts at the cut");
            assertEquals(before.endCh(), y.endCh(), 1e-6, "second half keeps the parent's end");

            double after = covered("condition", "section_label", "start_chainage", "end_chainage", first)
                         + covered("condition", "section_label", "start_chainage", "end_chainage", second);
            assertEquals(condCoveredBefore, after, 0.01,
                    "condition rows cover a different length after the split");
            assertTrue(rows("condition", "section_label", second) > 0,
                    "the second half received no rows — they were swept back into the first");
            assertBounded("condition", first, Math.abs(x.refLen()));
            assertBounded("condition", second, Math.abs(y.refLen()));

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-reuse-split-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                rejoinDividedRows(parent, cutLocal);
            }
        }

        SectionLineage.Section restored = lineage.loadSection(parent);
        assertNotNull(restored, "the parent section was not restored");
        assertEquals(before.startCh(), restored.startCh(), 1e-6, "restored start chainage");
        assertEquals(before.endCh(), restored.endCh(), 1e-6, "restored end chainage");
        assertEquals(0, rows("roads", labelColumn().replace("\"", ""), first), "first half must be gone");
        assertEquals(condBefore, rows("condition", "section_label", parent),
                "the section was not left exactly as it was found");
        assertEquals(condCoveredBefore,
                covered("condition", "section_label", "start_chainage", "end_chainage", parent), 0.01,
                "condition rows cover a different length after the undo");
    }

    /* ================= doing it twice ================= */

    /**
     * Merge → undo → merge → undo. The second pass must work exactly like the first.
     *
     * <p>An undo does not restore the road rows it replaced, it re-INSERTS them, so the sections
     * come back with new surrogate keys and their dependent rows come back by arithmetic rather
     * than from a copy. Nothing guarantees the result is a fixed point of the operation — and if
     * it is not, the damage only shows on the SECOND cycle, when a merge re-runs against rows an
     * undo produced. This asserts the whole state is identical after each cycle, so drift of a
     * metre or a row cannot accumulate unnoticed.
     */
    @Test
    void aMergeCanBeUndoneRedoneAndUndoneAgain() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no adjacent same-road pair in this database");
        String a = pair.get(0), b = pair.get(1);

        SectionLineage.Section sa0 = lineage.loadSection(a), sb0 = lineage.loadSection(b);
        long rowsA = rows("condition", "section_label", a), rowsB = rows("condition", "section_label", b);
        long astA = rows("road_assets", "section_label", a), astB = rows("road_assets", "section_label", b);
        double covA = covered("condition", "section_label", "start_chainage", "end_chainage", a);
        double covB = covered("condition", "section_label", "start_chainage", "end_chainage", b);

        for (int cycle = 1; cycle <= 2; cycle++) {
            Map<String, Object> m = lineage.applyMerge(List.of(a, b), a, true, true, Map.of(), "it-cycle" + cycle);
            assertEquals("ok", m.get("status"), "cycle " + cycle + " merge: " + m.get("message"));
            long id = ((Number) m.get("change_id")).longValue();

            assertEquals(Math.abs(sa0.refLen()) + Math.abs(sb0.refLen()),
                    Math.abs(lineage.loadSection(a).refLen()), 1e-6, "cycle " + cycle + " merged length");
            assertEquals(rowsA + rowsB, rows("condition", "section_label", a),
                    "cycle " + cycle + ": condition rows lost by the merge");

            Map<String, Object> u = lineage.undo(id, true, "it-cycle" + cycle + "-undo");
            assertEquals("ok", u.get("status"),
                    "cycle " + cycle + " undo refused: " + u.get("message"));

            /* Back to exactly where the cycle started — checked every time, not just at the end,
               so a cycle that drifts is caught on the cycle that caused it. */
            SectionLineage.Section sa = lineage.loadSection(a), sb = lineage.loadSection(b);
            assertNotNull(sa, "cycle " + cycle + ": A not restored");
            assertNotNull(sb, "cycle " + cycle + ": B not restored");
            assertEquals(sa0.startCh(), sa.startCh(), 1e-6, "cycle " + cycle + ": A start chainage");
            assertEquals(sa0.endCh(), sa.endCh(), 1e-6, "cycle " + cycle + ": A end chainage");
            assertEquals(sb0.startCh(), sb.startCh(), 1e-6, "cycle " + cycle + ": B start chainage");
            assertEquals(sb0.endCh(), sb.endCh(), 1e-6, "cycle " + cycle + ": B end chainage");
            assertEquals(rowsA, rows("condition", "section_label", a), "cycle " + cycle + ": A condition rows");
            assertEquals(rowsB, rows("condition", "section_label", b), "cycle " + cycle + ": B condition rows");
            assertEquals(astA, rows("road_assets", "section_label", a), "cycle " + cycle + ": A assets");
            assertEquals(astB, rows("road_assets", "section_label", b), "cycle " + cycle + ": B assets");
            assertEquals(covA, covered("condition", "section_label", "start_chainage", "end_chainage", a), 0.01,
                    "cycle " + cycle + ": A coverage drifted");
            assertEquals(covB, covered("condition", "section_label", "start_chainage", "end_chainage", b), 0.01,
                    "cycle " + cycle + ": B coverage drifted");
            System.out.println("[cycle " + cycle + "] merge + undo returned the pair exactly as it was");
        }
    }

    /**
     * Split → undo → split → undo.
     *
     * <p>Row COUNT is deliberately not asserted between cycles: a split divides a condition row
     * that straddles the cut and the undo leaves it as two adjacent rows covering the original
     * span, because re-joining them means choosing which of two equal measurements survives.
     * The test re-joins them itself — it made the division a moment ago, so here the answer is
     * known — and asserts on COVERAGE, which a bad re-base changes and an honest division does not.
     */
    @Test
    void aSplitCanBeUndoneRedoneAndUndoneAgain() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section carrying condition data");

        SectionLineage.Section before = lineage.loadSection(parent);
        double cutAbs = before.startCh() + (before.endCh() - before.startCh()) / 2;
        double cutLocal = cutAbs - before.startCh();
        long condBefore = rows("condition", "section_label", parent);
        long astBefore = rows("road_assets", "section_label", parent);
        double covBefore = covered("condition", "section_label", "start_chainage", "end_chainage", parent);

        for (int cycle = 1; cycle <= 2; cycle++) {
            String first = parent + "/C" + cycle + "a", second = parent + "/C" + cycle + "b";
            Map<String, Object> s = lineage.applySplit(parent, cutAbs, first, second, true, true,
                    Map.of(), Map.of(), "it-scycle" + cycle);
            assertEquals("ok", s.get("status"), "cycle " + cycle + " split: " + s.get("message"));
            long id = ((Number) s.get("change_id")).longValue();

            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), parent),
                    "cycle " + cycle + ": the parent row should be gone while split");
            assertNotNull(lineage.loadSection(first), "cycle " + cycle + ": first half missing");
            assertNotNull(lineage.loadSection(second), "cycle " + cycle + ": second half missing");

            Map<String, Object> u = lineage.undo(id, true, "it-scycle" + cycle + "-undo");
            assertEquals("ok", u.get("status"),
                    "cycle " + cycle + " undo refused: " + u.get("message"));
            rejoinDividedRows(parent, cutLocal);

            SectionLineage.Section back = lineage.loadSection(parent);
            assertNotNull(back, "cycle " + cycle + ": the parent was not restored");
            assertEquals(before.startCh(), back.startCh(), 1e-6, "cycle " + cycle + ": start chainage");
            assertEquals(before.endCh(), back.endCh(), 1e-6, "cycle " + cycle + ": end chainage");
            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), first),
                    "cycle " + cycle + ": first half still present after the undo");
            assertEquals(0, rows("roads", labelColumn().replace("\"", ""), second),
                    "cycle " + cycle + ": second half still present after the undo");
            assertEquals(condBefore, rows("condition", "section_label", parent),
                    "cycle " + cycle + ": condition row count drifted");
            assertEquals(astBefore, rows("road_assets", "section_label", parent),
                    "cycle " + cycle + ": assets were lost or duplicated");
            assertEquals(covBefore, covered("condition", "section_label", "start_chainage", "end_chainage", parent),
                    0.01, "cycle " + cycle + ": coverage drifted");
            System.out.println("[scycle " + cycle + "] split + undo returned \"" + parent + "\" exactly as it was");
        }
    }

    /**
     * A cut through an UNCLASSIFIED line asset is flagged, and the alternative it suggests
     * must actually be clear.
     *
     * <p>FWD and furniture lines are divided and bridges are refused, so neither reaches this
     * warning. It is the fallback for an asset type nobody has classified yet — the safe
     * default being to move it whole and tell the operator, rather than guess whether half of
     * it is still a true statement.
     *
     * <p>A condition row or video clip the cut crosses is divided; a line asset is assigned
     * whole by its start chainage, so one starting before the cut keeps an end chainage past
     * the first half's length and stops placing correctly. That already happened once on this
     * network. The preview now names them and proposes a chainage that crosses none — and the
     * proposal is only worth anything if re-previewing there is genuinely silent, which is what
     * this asserts rather than trusting the arithmetic.
     */
    @Test
    void aSplitThroughALineAssetWarnsAndOffersAClearChainage() {
        /* A section with no line assets of its own, so the only straddler is the one this test
           puts there. Real sections vary — on some, existing assets run edge to edge and there
           is genuinely nowhere clear to move to, which would skip the half of this test that
           matters most. */
        String parent = sectionWithNoLineAssets();
        assumeTrue(parent != null, "no clean section to place a fixture asset on");

        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double a = len * 0.40, b = len * 0.60;           // well inside, so both edges are clear
        double cut = (a + b) / 2;                        // straight through the middle of it

        long mark = assetWatermark();
        try {
            jdbc.update("INSERT INTO road_assets (section_label, asset_type, start_chainage, end_chainage) "
                      + "VALUES (?,?,?,?)", parent, "retaining_wall", a, b);   // deliberately unclassified
            System.out.printf("[straddle] %s (%.0f m): fixture asset %.0f–%.0f m, cutting at %.0f m%n",
                    parent, len, a, b, cut);

            Map<String, Object> p = lineage.previewSplit(parent, s.startCh() + cut,
                    parent + "/S1", parent + "/S2", "it");
            assertEquals("ok", p.get("status"), String.valueOf(p.get("message")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warns = (List<Map<String, Object>>) p.get("warnings");
            Map<String, Object> straddle = null;
            for (Map<String, Object> w : warns)
                if ("line_assets_straddle_split".equals(w.get("kind"))) straddle = w;
            assertNotNull(straddle, "no warning that the cut passes through a line asset: " + warns);
            assertEquals("high", straddle.get("severity"), "the operator must have to acknowledge this");
            System.out.println("[straddle] " + straddle.get("message"));

            /* The suggested chainage must itself be clear, or the advice just sends the operator
               from one broken cut to another. Asserted by re-previewing there, not by trusting
               the arithmetic that produced it. */
            Object sug = straddle.get("suggested_local_chainage");
            assertNotNull(sug, "an asset sitting mid-section has clear ground either side of it");
            double suggested = ((Number) sug).doubleValue();
            assertTrue(suggested > 0 && suggested < len,
                    "the suggested cut " + suggested + " is outside the section");
            assertTrue(Math.abs(suggested - a) < 0.5 || Math.abs(suggested - b) < 0.5,
                    "expected the suggestion at one end of the asset, got " + suggested);

            Map<String, Object> p2 = lineage.previewSplit(parent, s.startCh() + suggested,
                    parent + "/S1", parent + "/S2", "it");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warns2 = (List<Map<String, Object>>) p2.get("warnings");
            for (Map<String, Object> w : warns2)
                assertNotEquals("line_assets_straddle_split", w.get("kind"),
                        "the suggested chainage " + suggested + " still cuts a line asset: " + w.get("message"));
            System.out.printf("[straddle] suggested %.0f m is clear%n", suggested);

        } finally {
            dropAssetsAfter(mark);
        }
    }

    /**
     * A cut through a BRIDGE is refused outright, and the refusal says where to cut instead.
     *
     * <p>Half a bridge is not a bridge. Dividing it invents a structure that does not exist and
     * assigning it whole puts its deck partly outside the section that claims it, so unlike a
     * measurement there is no correct outcome to offer — the cut itself is the mistake. The
     * refusal has to name a clear chainage or it is a dead end, and that chainage must be clear
     * of EVERY structure, not just this one, which is what the re-preview asserts.
     */
    @Test
    void aSplitThroughABridgeIsRefusedAndNamesAClearChainage() {
        String parent = sectionWithNoLineAssets();
        assumeTrue(parent != null, "no clean section to place a fixture bridge on");

        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double a = len * 0.40, b = len * 0.60, cut = (a + b) / 2;

        long mark = assetWatermark();
        try {
            jdbc.update("INSERT INTO road_assets (section_label, asset_type, start_chainage, end_chainage) "
                      + "VALUES (?,?,?,?)", parent, "bridge", a, b);
            System.out.printf("[bridge] %s: bridge %.0f–%.0f m, cutting at %.0f m%n", parent, a, b, cut);

            Map<String, Object> p = lineage.previewSplit(parent, s.startCh() + cut,
                    parent + "/B1", parent + "/B2", "it");
            assertEquals("refused", p.get("status"),
                    "a cut through a bridge must be refused, not merely warned about");
            String msg = String.valueOf(p.get("message"));
            assertTrue(msg.contains("bridge"), "the refusal must name the structure: " + msg);
            System.out.println("[bridge] " + msg);

            /* Refusing is only useful if it says where to go, and that place must work. */
            assertTrue(msg.contains(String.valueOf(Math.round(a))) || msg.contains(String.valueOf(Math.round(b))),
                    "the refusal must name a chainage clear of the bridge: " + msg);
            Map<String, Object> p2 = lineage.previewSplit(parent, s.startCh() + a,
                    parent + "/B1", parent + "/B2", "it");
            assertEquals("ok", p2.get("status"),
                    "cutting at the bridge's own start should be allowed: " + p2.get("message"));

            /* And the apply path must refuse too, not just the preview. */
            Map<String, Object> ap = lineage.applySplit(parent, s.startCh() + cut,
                    parent + "/B1", parent + "/B2", true, true, Map.of(), Map.of(), "it-bridge");
            assertEquals("refused", ap.get("status"), "apply must refuse a cut through a bridge");
            assertNotNull(lineage.loadSection(parent), "a refused split must change nothing");

        } finally {
            dropAssetsAfter(mark);
        }
    }

    /**
     * A cut through an FWD stretch divides it, the way a condition row is divided.
     *
     * <p>An FWD reading describes a length of pavement, so each half still describes the ground
     * under it. The invariant is coverage: two halves must span exactly what one row spanned,
     * and neither may reach past the end of the section it landed on.
     */
    @Test
    void aSplitThroughAnFwdStretchDividesIt() {
        String parent = sectionWithNoLineAssets();
        assumeTrue(parent != null, "no clean section to place a fixture FWD stretch on");

        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double a = len * 0.40, b = len * 0.60, cut = (a + b) / 2;
        String first = parent + "/F1", second = parent + "/F2";

        long mark = assetWatermark();
        Long changeId = null;
        try {
            jdbc.update("INSERT INTO road_assets (section_label, asset_type, start_chainage, end_chainage) "
                      + "VALUES (?,?,?,?)", parent, "fwd", a, b);
            System.out.printf("[fwd] %s: stretch %.0f–%.0f m, cutting at %.0f m%n", parent, a, b, cut);

            Map<String, Object> r = lineage.applySplit(parent, s.startCh() + cut, first, second,
                    true, true, Map.of(), Map.of(), "it-fwdcut");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            double onFirst = covered("road_assets", "section_label", "start_chainage", "end_chainage", first);
            double onSecond = covered("road_assets", "section_label", "start_chainage", "end_chainage", second);
            assertEquals(b - a, onFirst + onSecond, 0.01,
                    "the two halves must cover exactly what the one stretch covered");
            assertTrue(onFirst > 0 && onSecond > 0,
                    "the stretch was not divided — one half got all of it (" + onFirst + " / " + onSecond + ")");
            assertBounded("road_assets", first, Math.abs(lineage.loadSection(first).refLen()));
            assertBounded("road_assets", second, Math.abs(lineage.loadSection(second).refLen()));
            System.out.printf("[fwd] divided into %.0f m + %.0f m%n", onFirst, onSecond);

        } finally {
            if (changeId != null) lineage.undo(changeId, true, "it-fwdcut-undo");
            dropAssetsAfter(mark);
        }
    }

    /**
     * A point sitting exactly on the cut goes to the SECOND half, at its chainage 0.
     *
     * <p>The boundary belongs to one half or the other and the choice has to be deliberate,
     * because a culvert or a traffic station landing on the join is common — chainages get
     * rounded to the same 10 m the cut is chosen at. Chainage ranges are half-open, [start,end):
     * the first half ends AT the cut and the second half starts AT it, so the cut metre is the
     * second half's first metre. Assigning it to the first half instead would put the point at
     * exactly that half's length — the one chainage its own geometry cannot place, because the
     * linear reference would be a fraction of 1.0 at the very end of the line.
     *
     * <p>A LINE that merely ends at the cut stays with the first half, which is the same rule
     * seen from the other side: it occupies ground before the boundary, not at it.
     */
    @Test
    void aPointExactlyOnTheCutGoesToTheSecondHalfAtZero() {
        String parent = sectionWithNoLineAssets();
        assumeTrue(parent != null, "no clean section to place fixture points on");

        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double cut = Math.round(len / 2);
        String first = parent + "/Q1", second = parent + "/Q2";

        long mark = assetWatermark();
        Long changeId = null;
        try {
            /* Three points: just before the cut, exactly on it, just after. */
            for (double ch : new double[]{cut - 10, cut, cut + 10})
                jdbc.update("INSERT INTO road_assets (section_label, asset_type, start_chainage, end_chainage) "
                          + "VALUES (?,?,?,?)", parent, "culvert", ch, ch);
            /* And a line that merely ENDS at the cut — the mirror case. */
            jdbc.update("INSERT INTO road_assets (section_label, asset_type, start_chainage, end_chainage) "
                      + "VALUES (?,?,?,?)", parent, "fwd", cut - 100, cut);

            Map<String, Object> r = lineage.applySplit(parent, s.startCh() + cut, first, second,
                    true, true, Map.of(), Map.of(), "it-tie");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            List<Double> onFirst = culvertChainages(first), onSecond = culvertChainages(second);
            System.out.println("[tie] culverts on first=" + onFirst + " second=" + onSecond);

            assertEquals(List.of(cut - 10), onFirst, "only the point before the cut belongs to the first half");
            assertEquals(List.of(0.0, 10.0), onSecond,
                    "the point ON the cut must start the second half at 0, with the later one at 10");

            double endsAtCut = covered("road_assets", "section_label", "start_chainage", "end_chainage", first);
            assertEquals(100.0, endsAtCut, 0.01,
                    "a line ending exactly at the cut stays whole on the first half");
            assertEquals(0.0, covered("road_assets", "section_label", "start_chainage", "end_chainage", second),
                    0.01, "nothing of that line should have crossed to the second half");

        } finally {
            if (changeId != null) lineage.undo(changeId, true, "it-tie-undo");
            dropAssetsAfter(mark);
        }
    }

    private List<Double> culvertChainages(String label) {
        return jdbc.queryForList(
                "SELECT start_chainage FROM road_assets WHERE section_label = ? "
              + "AND asset_type = 'culvert' ORDER BY 1", Double.class, label);
    }

    /**
     * Highest {@code road_assets} id right now, so a test can remove exactly what it caused.
     *
     * <p>Deleting fixtures by section label is not enough: a split moves them onto the child
     * labels and DIVIDES some into a second row the test never inserted. Anything created after
     * this watermark is the test's, wherever it ended up — which is what stopped one test's
     * leftovers from being counted as another's coverage.
     */
    private long assetWatermark() {
        Long id = jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM road_assets", Long.class);
        return id == null ? 0 : id;
    }

    private void dropAssetsAfter(long watermark) {
        int n = jdbc.update("DELETE FROM road_assets WHERE id > ?", watermark);
        if (n > 0) System.out.println("[cleanup] removed " + n + " fixture asset row(s)");
    }

    /** A drawable section of decent length carrying no line assets at all. */
    private String sectionWithNoLineAssets() {
        List<String> found = jdbc.queryForList(
                "SELECT r." + labelColumn() + " FROM roads r "
              + "WHERE r.geom IS NOT NULL "
              + "  AND ST_GeometryType(ST_LineMerge(r.geom)) = 'ST_LineString' "
              + "  AND " + roadColumns.col("r", LayerAttributeCatalog.MEASURED_LENGTH)
              + "::double precision > 500 "
              + "  AND NOT EXISTS (SELECT 1 FROM road_assets d WHERE d.section_label = r." + labelColumn()
              + "                  AND d.end_chainage > d.start_chainage) "
              + "ORDER BY 1 LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    @Test
    void historyListsChangesAndSaysWhatCanStillBeUndone() {
        List<Map<String, Object>> h = lineage.history(20);
        System.out.println("[history] " + h.size() + " entr(ies)");
        for (Map<String, Object> e : h.subList(0, Math.min(5, h.size())))
            System.out.println("  " + e.get("id") + " " + e.get("operation") + " by " + e.get("username")
                    + " undoable=" + e.get("undoable")
                    + (e.get("undo_blocked") == null ? "" : " (" + e.get("undo_blocked") + ")"));
        for (Map<String, Object> e : h) assertNotNull(e.get("operation"));
    }
}
