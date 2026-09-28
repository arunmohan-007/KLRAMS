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
 * A section split passing straight through an NSV video clip.
 *
 * <p>Before the file window existed, a clip's chainage span WAS its whole file, so a clip could
 * not be divided: half a window would have played the whole recording across half the road. The
 * split now gives each half its own window into the same file, and the arithmetic that has to
 * hold is continuity — the point where the first half stops must be the point where the second
 * half starts, in the footage as well as on the ground.
 *
 * <p>The test creates its own clip row against a real section rather than depending on the
 * catalogue happening to hold one that straddles a convenient chainage, and removes it after.
 */
@SpringBootTest
class VideoClipSplitIT {

    @Autowired SectionLineageService lineage;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;
    @Autowired SurveyPeriodService periods;

    private String labelColumn() {
        return roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);
    }

    private String sectionWithRoom() {
        String end = roadColumns.col("r", LayerAttributeCatalog.ROAD_END_CHAINAGE);
        String start = roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE);
        String measured = roadColumns.col("r", LayerAttributeCatalog.MEASURED_LENGTH);
        List<String> f = jdbc.queryForList(
                "SELECT r." + labelColumn() + " FROM roads r WHERE r.geom IS NOT NULL "
              + "  AND ST_GeometryType(ST_LineMerge(r.geom)) = 'ST_LineString' "
              + "  AND " + end + "::double precision - " + start + "::double precision > 500 "
              + "  AND abs(abs(" + end + "::double precision - " + start + "::double precision) "
              + "        - " + measured + "::double precision) <= 0.5 "
              + "ORDER BY 1 LIMIT 1", String.class);
        return f.isEmpty() ? null : f.get(0);
    }

    /**
     * Re-joins the condition rows a split divided, so the suite leaves the database where it
     * found it however many times it runs.
     *
     * <p>An undo deliberately leaves a divided row as two adjacent rows — re-joining them means
     * choosing which of two equal measurements survives, which the code should not do silently.
     * This test created the division moments ago against a known cut, so it can undo it exactly.
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

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    @Test
    void aClipTheCutPassesThroughIsDividedIntoTwoWindowsOnOneFile() {
        String parent = sectionWithRoom();
        assumeTrue(parent != null, "no suitable section");
        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double cutAbs = s.startCh() + len / 2;
        double cut = len / 2;

        double clipFrom = cut - 100, clipTo = cut + 150;
        String file = "it-split-clip.mp4";
        jdbc.update("INSERT INTO road_video (section_label, video_file, direction, period_id, from_ch, to_ch) "
                  + "VALUES (?,?,?,?,?,?)", parent, file, "forward", periods.activePeriodId(), clipFrom, clipTo);

        String first = parent + "/V1", second = parent + "/V2";
        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true, Map.of(), Map.of(), "it-video");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            List<Map<String, Object>> clips = jdbc.queryForList(
                    "SELECT section_label, from_ch, to_ch, file_from_ch, file_to_ch FROM road_video "
                  + "WHERE video_file = ? ORDER BY section_label", file);
            System.out.println("[clip] " + clips);
            assertEquals(2, clips.size(), "the clip must have been divided in two");

            Map<String, Object> a = clips.get(0), b = clips.get(1);
            assertEquals(first, a.get("section_label"));
            assertEquals(second, b.get("section_label"));

            assertEquals(clipFrom, num(a.get("from_ch")), 1e-6, "first half keeps the clip's start");
            assertEquals(cut, num(a.get("to_ch")), 1e-6, "first half ends at the cut");
            assertEquals(clipFrom, num(a.get("file_from_ch")), 1e-6, "file still starts where it did");
            assertEquals(clipTo, num(a.get("file_to_ch")), 1e-6, "file still ends where it did");

            assertEquals(0.0, num(b.get("from_ch")), 1e-6, "second half starts at its own origin");
            assertEquals(clipTo - cut, num(b.get("to_ch")), 1e-6, "second half carries the remainder");
            assertEquals(clipFrom - cut, num(b.get("file_from_ch")), 1e-6,
                    "the file starts before this section — a negative file window is correct here");
            assertEquals(clipTo - cut, num(b.get("file_to_ch")), 1e-6);
            assertTrue(num(b.get("file_from_ch")) < 0,
                    "this is the case the whole column pair exists for");

            /* Continuity: the fraction of the file where the first half stops must be exactly
               where the second half starts. That fraction is what the player turns into video
               time, so a mismatch is a visible jump or a repeated stretch of road. */
            double fracEndA = (num(a.get("to_ch")) - num(a.get("file_from_ch")))
                            / (num(a.get("file_to_ch")) - num(a.get("file_from_ch")));
            double fracStartB = (num(b.get("from_ch")) - num(b.get("file_from_ch")))
                              / (num(b.get("file_to_ch")) - num(b.get("file_from_ch")));
            assertEquals(fracEndA, fracStartB, 1e-9,
                    "the halves do not meet in the footage — playback would jump or repeat");
            System.out.printf("[clip] both halves meet at %.4f of the file%n", fracEndA);

            double coveredA = num(a.get("to_ch")) - num(a.get("from_ch"));
            double coveredB = num(b.get("to_ch")) - num(b.get("from_ch"));
            assertEquals(clipTo - clipFrom, coveredA + coveredB, 1e-6,
                    "the divided clip covers a different length of road");

        } finally {
            if (changeId != null) {
                Map<String, Object> u = lineage.undo(changeId, true, "it-video-undo");
                assertEquals("ok", u.get("status"), "undo failed: " + u.get("message"));
                rejoinDividedRows(parent, cut);
            }
            jdbc.update("DELETE FROM road_video WHERE video_file = ?", file);
        }

        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM road_video WHERE video_file = ?", Long.class, file),
                "the test clip rows were not cleaned up");
    }

    /**
     * The preview must describe the clip division it is previewing.
     *
     * <p>It did not: road_video was reported as "moved whole to the half containing the start
     * chainage" with a second-half count of 0, while the commit divided the clip and created a
     * row on the second half. The earlier preview-vs-commit test never caught it because the
     * section it picked happened to carry no video.
     */
    @Test
    void thePreviewReportsAStraddlingClipAsDividedAndCountsBothHalves() {
        String parent = sectionWithRoom();
        assumeTrue(parent != null, "no suitable section");
        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double cutAbs = s.startCh() + len / 2, cut = len / 2;

        String file = "it-preview-clip.mp4";
        jdbc.update("INSERT INTO road_video (section_label, video_file, direction, period_id, from_ch, to_ch) "
                  + "VALUES (?,?,?,?,?,?)", parent, file, "forward",
                periods.activePeriodId(), cut - 100, cut + 150);

        String first = parent + "/Q1", second = parent + "/Q2";
        Long changeId = null;
        try {
            Map<String, Object> preview = lineage.previewSplit(parent, cutAbs, first, second, "it");
            assertEquals("ok", preview.get("status"), String.valueOf(preview.get("message")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables = (List<Map<String, Object>>) preview.get("tables");
            Map<String, Object> video = tables.stream()
                    .filter(t -> "road_video".equals(t.get("table"))).findFirst().orElse(null);
            assertNotNull(video, "the preview must list road_video when the section carries a clip");
            System.out.println("[clip-preview] " + video);

            /* At least one — not exactly one. The section may carry other clips, including one a
               sibling test is midway through, and pinning the count to 1 made this fail for a
               reason that had nothing to do with what it is checking: that a straddling clip is
               reported as DIVIDED and gives the second half something. */
            long straddling = ((Number) video.get("straddling")).longValue();
            assertTrue(straddling >= 1, "the clip straddles the cut, so straddling must be >= 1");
            assertEquals(straddling, ((Number) video.get("divided")).longValue(),
                    "every straddling clip is divided, not assigned whole");
            assertTrue(((Number) video.get("to_second")).longValue() > 0,
                    "the second half must receive its part of the clip, not zero");
            assertTrue(String.valueOf(video.get("straddle_handling")).contains("divided"),
                    "the wording must say the clip is divided: " + video.get("straddle_handling"));

            long predictedFirst = ((Number) video.get("to_first")).longValue();
            long predictedSecond = ((Number) video.get("to_second")).longValue();

            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true,
                    Map.of(), Map.of(), "it-clip-preview");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            assertEquals(predictedFirst, (long) jdbc.queryForObject(
                    "SELECT count(*) FROM road_video WHERE section_label = ?", Long.class, first),
                    "the first half did not get the predicted number of clips");
            assertEquals(predictedSecond, (long) jdbc.queryForObject(
                    "SELECT count(*) FROM road_video WHERE section_label = ?", Long.class, second),
                    "the second half did not get the predicted number of clips");
            System.out.println("[clip-preview] commit matched: first=" + predictedFirst
                    + " second=" + predictedSecond);

        } finally {
            if (changeId != null) {
                lineage.undo(changeId, true, "it-clip-preview-undo");
                rejoinDividedRows(parent, cut);
            }
            jdbc.update("DELETE FROM road_video WHERE video_file = ?", file);
        }
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT count(*) FROM road_video WHERE video_file LIKE 'it-%'", Long.class),
                "a test clip was left behind — that is what makes a sibling test see an extra "
              + "straddling clip");
    }

    @Test
    void aClipWhollyPastTheCutKeepsItsFileWindowAlignedAfterTheShift() {
        String parent = sectionWithRoom();
        assumeTrue(parent != null, "no suitable section");
        SectionLineage.Section s = lineage.loadSection(parent);
        double len = Math.abs(s.refLen());
        double cutAbs = s.startCh() + len / 2, cut = len / 2;

        // Entirely past the cut, and carrying an EXPLICIT file window wider than the clip.
        double clipFrom = cut + 50, clipTo = cut + 150;
        double fileFrom = cut + 20, fileTo = cut + 200;
        String file = "it-shift-clip.mp4";
        jdbc.update("INSERT INTO road_video (section_label, video_file, direction, period_id, "
                  + "from_ch, to_ch, file_from_ch, file_to_ch) VALUES (?,?,?,?,?,?,?,?)",
                parent, file, "forward", periods.activePeriodId(), clipFrom, clipTo, fileFrom, fileTo);

        String first = parent + "/W1", second = parent + "/W2";
        Long changeId = null;
        try {
            Map<String, Object> r = lineage.applySplit(parent, cutAbs, first, second, true, true, Map.of(), Map.of(), "it-video2");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            changeId = ((Number) r.get("change_id")).longValue();

            Map<String, Object> c = jdbc.queryForMap(
                    "SELECT section_label, from_ch, to_ch, file_from_ch, file_to_ch FROM road_video "
                  + "WHERE video_file = ?", file);
            System.out.println("[shift] " + c);
            assertEquals(second, c.get("section_label"));
            assertEquals(clipFrom - cut, num(c.get("from_ch")), 1e-6);
            assertEquals(clipTo - cut, num(c.get("to_ch")), 1e-6);
            /* The file window has to move with the clip. Shifting only the clip would leave the
               file window measured from the old section's origin, and the player maps one onto
               the other — the clip would play the wrong stretch of footage, with no error. */
            assertEquals(fileFrom - cut, num(c.get("file_from_ch")), 1e-6,
                    "the file window did not shift with the clip");
            assertEquals(fileTo - cut, num(c.get("file_to_ch")), 1e-6,
                    "the file window did not shift with the clip");

        } finally {
            if (changeId != null) {
                lineage.undo(changeId, true, "it-video2-undo");
                rejoinDividedRows(parent, cut);
            }
            jdbc.update("DELETE FROM road_video WHERE video_file = ?", file);
        }
    }
}
