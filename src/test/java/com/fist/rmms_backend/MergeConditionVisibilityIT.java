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
 * Merges two real sections and follows the condition data all the way to what the map draws.
 *
 * <p>{@link SectionSplitMergeIT} already proves the {@code condition} rows themselves survive a
 * merge. This test exists for the question that came after it — "so for merge, condition data
 * won't come?" — which is about a different table. The map does not draw {@code condition}; it
 * draws {@code condition_segments}, which is CUT from the centreline and is therefore listed in
 * {@code SectionLineageService.DERIVED} and deliberately left alone by the merge.
 *
 * <p>So the sequence checked here is the one the user actually experiences:
 * <ol>
 *   <li>the condition rows move correctly, and</li>
 *   <li>the map is nevertheless still showing the old two-section picture, because the derived
 *       layer is stale — this is asserted, not assumed, since it is the whole reason the
 *       "Rebuild now" button exists, and</li>
 *   <li>one rebuild puts the drawn layer onto the merged label, covering the full merged length
 *       with nothing left behind on the consumed label.</li>
 * </ol>
 *
 * <p>Everything is undone and rebuilt again in a {@code finally}, so the development database is
 * left as it was found even if an assertion fails partway through.
 */
@SpringBootTest
class MergeConditionVisibilityIT {

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

    private long drawnSegments(String label) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM condition_segments WHERE section_label = ?", Long.class, label);
    }

    /** How far along the section the drawn layer actually reaches — 0 when it draws nothing. */
    private double drawnReach(String label) {
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(MAX(end_chainage), 0) FROM condition_segments WHERE section_label = ?",
                Double.class, label);
        return d == null ? 0 : d;
    }

    @Test
    void aMergeMovesTheConditionRowsAndOneRebuildPutsThemBackOnTheMap() {
        List<String> pair = adjacentPairOnOneRoad();
        assumeTrue(pair != null, "no mergeable adjacent same-road pair in this database");
        String a = pair.get(0), b = pair.get(1);

        SectionLineage.Section sa = lineage.loadSection(a), sb = lineage.loadSection(b);
        double lenA = Math.abs(sa.refLen()), lenB = Math.abs(sb.refLen());
        long rowsA = conditionRows(a), rowsB = conditionRows(b);

        /* The drawn layer must be current before the merge, or "it went stale" proves nothing. */
        segments.buildSegments();
        long drawnA = drawnSegments(a), drawnB = drawnSegments(b);

        System.out.printf("[before] %s: %.1f m, %d condition rows, %d drawn segments%n",
                a, lenA, rowsA, drawnA);
        System.out.printf("[before] %s: %.1f m, %d condition rows, %d drawn segments%n",
                b, lenB, rowsB, drawnB);
        assumeTrue(rowsB > 0, "the consumed section carries no condition rows — nothing to follow");

        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applyMerge(List.of(a, b), a, true, true, Map.of(), "it-merge-vis");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();
            System.out.println("[merge] " + r.get("moved"));

            /* 1. The rows themselves came across, re-based onto the merged origin. */
            assertEquals(rowsA + rowsB, conditionRows(a),
                    "condition rows were lost in the merge");
            assertEquals(0L, conditionRows(b),
                    "condition rows were left behind on the consumed label");
            assertEquals(rowsB, (long) jdbc.queryForObject(
                            "SELECT count(*) FROM condition WHERE section_label = ? AND start_chainage >= ?",
                            Long.class, a, lenA),
                    "the consumed section's rows were not shifted past the first section's length");
            System.out.printf("[after ] condition: %s now holds %d rows, %s holds %d%n",
                    a, conditionRows(a), b, conditionRows(b));

            /* 2. ...but the map has not changed yet. This is the reported symptom, and it is a
                  deliberate consequence of condition_segments being derived, not migrated. */
            assertEquals(drawnB, drawnSegments(b),
                    "the drawn layer changed on its own — DERIVED is supposed to be left untouched");
            System.out.printf("[after ] drawn (stale): %s = %d segments, %s = %d segments%n",
                    a, drawnSegments(a), b, drawnSegments(b));

            /* 3. One rebuild — exactly what the "Rebuild now" button calls. */
            int built = segments.buildSegments();
            System.out.println("[rebuild] condition_segments rebuilt: " + built + " rows");

            assertEquals(0L, drawnSegments(b),
                    "the consumed label still draws segments after the rebuild");
            assertTrue(drawnSegments(a) >= drawnA + drawnB - 1,
                    "the merged label draws fewer segments (" + drawnSegments(a)
                  + ") than the two parts did (" + drawnA + " + " + drawnB + ")");
            /* The drawn layer must now reach into what used to be the second section. If the
               offset were wrong the rows would still be there but bunched inside the first
               section's length — present, drawn, and in the wrong place. */
            assertTrue(drawnReach(a) > lenA,
                    "the drawn layer stops at " + drawnReach(a) + " m, inside the first section's "
                  + lenA + " m — the merged half is not being drawn");
            System.out.printf("[after ] drawn (rebuilt): %s = %d segments reaching %.1f m of %.1f m%n",
                    a, drawnSegments(a), drawnReach(a), lenA + lenB);

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                segments.buildSegments();
                System.out.println("[undo] " + u.get("message") + " (derived layers rebuilt)");
            }
        }

        assertEquals(rowsA, conditionRows(a), "A's condition rows were not returned");
        assertEquals(rowsB, conditionRows(b), "B's condition rows were not returned");
    }

    /**
     * An adjacent same-road pair whose centrelines actually join.
     *
     * <p>Abutting chainage does not mean touching geometry: in this network only a minority of
     * chainage-adjacent pairs can be merged at all, so the geometry join is part of the search
     * rather than something the test discovers by failing.
     */
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
              + "  AND EXISTS (SELECT 1 FROM condition d WHERE d.section_label = a." + lbl + ") "
              + "  AND EXISTS (SELECT 1 FROM condition d WHERE d.section_label = b." + lbl + ") "
              + "  AND ST_GeometryType(ST_LineMerge(ST_Union(a.geom, b.geom))) = 'ST_LineString' "
              + "ORDER BY 1 LIMIT 1");
        if (f.isEmpty()) return null;
        return List.of(String.valueOf(f.get(0).get("first")), String.valueOf(f.get(0).get("second")));
    }
}
