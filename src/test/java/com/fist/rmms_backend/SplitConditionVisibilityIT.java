package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Splits a real section and follows the condition data through to what the map draws.
 *
 * <p>The sibling of {@link MergeConditionVisibilityIT}, for the other half of the module and for
 * the report that came with it — "for the splitted sections, no condition data". The rows do move;
 * what the map draws is {@code condition_segments}, which is CUT from the centreline, listed in
 * {@code SectionLineageService.DERIVED}, and therefore deliberately not migrated. A split makes
 * that especially visible because the parent label ceases to exist: until the rebuild runs, the
 * drawn layer is keyed entirely to a section that is gone, and both new halves draw nothing at
 * all. That is exactly the reported symptom, and it is asserted here rather than assumed.
 *
 * <p>The cut is placed deliberately INSIDE a condition row, so the division path is exercised:
 * a straddling row is split in two, both halves keeping the same measurements. A cut landing on
 * a row boundary would move rows whole and never test that.
 *
 * <p>Everything is undone, re-joined and rebuilt in a {@code finally}. The re-join is needed
 * because an undo leaves a divided row as two adjacent rows by design.
 */
@SpringBootTest
class SplitConditionVisibilityIT {

    @Autowired SectionLineageService lineage;
    @Autowired SegmentService segments;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;

    private String labelColumn() {
        return roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);
    }

    private long conditionRows(String label) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM condition WHERE section_label = ?", Long.class, label);
    }

    /** Total metres of section covered by the condition rows — a bad re-base changes this. */
    private double conditionCovered(String label) {
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(SUM(abs(end_chainage - start_chainage)), 0) FROM condition "
              + "WHERE section_label = ?", Double.class, label);
        return d == null ? 0 : d;
    }

    private long drawnSegments(String label) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM condition_segments WHERE section_label = ?", Long.class, label);
    }

    private double drawnReach(String label) {
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(MAX(end_chainage), 0) FROM condition_segments WHERE section_label = ?",
                Double.class, label);
        return d == null ? 0 : d;
    }

    @Test
    void aSplitMovesTheConditionRowsAndOneRebuildPutsBothHalvesOnTheMap() {
        String parent = splittableSectionWithData();
        assumeTrue(parent != null, "no splittable section carrying condition data");

        SectionLineage.Section before = lineage.loadSection(parent);
        double len = Math.abs(before.refLen());
        double cutLocal = cutInsideAConditionRow(parent, len);
        double cutAbs = before.startCh() + cutLocal;
        String first = parent + "/V1", second = parent + "/V2";

        long rowsBefore = conditionRows(parent);
        double coveredBefore = conditionCovered(parent);

        /* The drawn layer must be current beforehand, or "it went stale" proves nothing. */
        segments.buildSegments();
        long drawnBefore = drawnSegments(parent);

        System.out.printf("[before] %s: %.1f m, %d condition rows covering %.1f m, %d drawn segments%n",
                parent, len, rowsBefore, coveredBefore, drawnBefore);
        System.out.printf("[split ] cutting at %.1f m (local) — inside a condition row%n", cutLocal);

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second,
                    true, true, Map.of(), Map.of(), "it-split-vis");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();
            System.out.println("[split ] " + r.get("moved"));

            /* 1. The rows came across, onto the correct half, each half re-based to its own
                  0-origin. A divided row adds one to the total — coverage must not change. */
            long rowsFirst = conditionRows(first), rowsSecond = conditionRows(second);
            assertEquals(0L, conditionRows(parent), "condition rows were left on the parent label");
            assertTrue(rowsFirst > 0, "the first half received no condition rows");
            assertTrue(rowsSecond > 0, "the second half received no condition rows");
            assertTrue(rowsFirst + rowsSecond >= rowsBefore,
                    "condition rows were lost: " + rowsFirst + " + " + rowsSecond
                  + " is fewer than the " + rowsBefore + " the parent had");
            assertEquals(coveredBefore, conditionCovered(first) + conditionCovered(second), 0.01,
                    "the halves cover a different length than the parent did — a re-base was wrong");
            assertTrue(rowsFirst + rowsSecond > rowsBefore,
                    "the cut was inside a row, so one row must have been divided in two");
            System.out.printf("[after ] condition: %s = %d rows / %.1f m, %s = %d rows / %.1f m%n",
                    first, rowsFirst, conditionCovered(first),
                    second, rowsSecond, conditionCovered(second));

            /* Both halves must start at 0 — the second half is the one that gets this wrong,
               and a missed re-base leaves its rows sitting past the end of a shorter section. */
            assertEquals(0.0, jdbc.queryForObject(
                    "SELECT MIN(start_chainage) FROM condition WHERE section_label = ?",
                    Double.class, second), 0.01,
                    "the second half was not re-based to a 0 origin");
            assertEquals(cutLocal, conditionCovered(first), 0.01,
                    "the first half should cover exactly up to the cut");

            /* 2. ...and yet the map still shows the parent, which no longer exists. */
            assertEquals(drawnBefore, drawnSegments(parent),
                    "the drawn layer changed on its own — DERIVED is supposed to be left untouched");
            assertEquals(0L, drawnSegments(first) + drawnSegments(second),
                    "the halves should draw nothing until the rebuild runs");
            System.out.printf("[after ] drawn (stale): parent %s = %d segments, halves = 0 + 0%n",
                    parent, drawnSegments(parent));

            /* 3. One rebuild — what the "Rebuild now" button calls. */
            int built = segments.buildSegments();
            System.out.println("[rebuild] condition_segments rebuilt: " + built + " rows");

            assertEquals(0L, drawnSegments(parent),
                    "the parent label still draws segments after the rebuild");
            assertTrue(drawnSegments(first) > 0 && drawnSegments(second) > 0,
                    "a half draws nothing after the rebuild: " + drawnSegments(first)
                  + " + " + drawnSegments(second));
            /* Each half must be drawn within its OWN length. If the second half's rows had kept
               the parent's chainage they would still draw — just off the end of the section. */
            assertTrue(drawnReach(first) <= cutLocal + 0.5,
                    "the first half draws out to " + drawnReach(first)
                  + " m, past its own " + cutLocal + " m");
            assertTrue(drawnReach(second) <= (len - cutLocal) + 0.5,
                    "the second half draws out to " + drawnReach(second)
                  + " m, past its own " + (len - cutLocal) + " m");
            System.out.printf("[after ] drawn (rebuilt): %s = %d segs reaching %.1f/%.1f m, "
                            + "%s = %d segs reaching %.1f/%.1f m%n",
                    first, drawnSegments(first), drawnReach(first), cutLocal,
                    second, drawnSegments(second), drawnReach(second), len - cutLocal);

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                rejoinDividedRows(parent, cutLocal);
                segments.buildSegments();
                System.out.println("[undo] " + u.get("message") + " (re-joined, derived rebuilt)");
            }
        }

        assertEquals(rowsBefore, conditionRows(parent),
                "the section was not left exactly as it was found");
        assertEquals(coveredBefore, conditionCovered(parent), 0.01,
                "the section covers a different length after the undo");
    }

    /**
     * A local chainage strictly inside one of the section's condition rows, near the middle.
     *
     * <p>Rows here are 100 m intervals, so a plain midpoint tends to land exactly on a boundary
     * and every row would move whole — leaving the division path, the one that actually rewrites
     * a row's measurements, untested.
     */
    private double cutInsideAConditionRow(String label, double len) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT start_chainage AS s, end_chainage AS e FROM condition "
              + "WHERE section_label = ? AND end_chainage > start_chainage + 1 "
              + "  AND start_chainage > 0 AND end_chainage < ? "
              + "ORDER BY abs((start_chainage + end_chainage) / 2 - ?) LIMIT 1",
                label, len, len / 2).get(0);
        double s = ((Number) row.get("s")).doubleValue(), e = ((Number) row.get("e")).doubleValue();
        return Math.round((s + e) / 2);
    }

    /** A section with a consistent chainage frame, condition rows, and a mergeable line. */
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

    /**
     * Re-joins the condition rows this test's split divided.
     *
     * <p>An undo deliberately leaves a divided row as two adjacent rows — same coverage, one
     * extra row — because re-joining means choosing which of two equal measurements survives.
     * Here the answer is known, because this test created the division a moment ago.
     */
    private void rejoinDividedRows(String label, double cut) {
        int rejoined = 0;
        /* Matching "adjacent at the cut" alone is NOT enough, and getting that wrong once
           destroyed two genuine survey rows: 3900-4000 and 4000-4100 on a real section are
           adjacent at 4000 and were merged into one 200 m row, discarding the second's
           measurements. A row the split DIVIDED is identifiable: both halves carry identical
           measurements (a division copies them), and the far half is an INSERT so its id is
           newer. Requiring both leaves genuine neighbours — which differ in at least one
           measurement — untouched. */
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
}
