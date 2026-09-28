package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A section may carry several video clips, and they must still be there after a restart.
 *
 * <p>{@code VideoService.ensureSchema()} runs on every boot. It used to call
 * {@code dedupeKeepingLatest(section_label, period_id)} — correct when a UNIQUE index on that
 * pair was about to be created, and pure data loss once the index was dropped to allow several
 * clips per section. The two lines sat next to each other, so nothing looked wrong: the dedupe
 * deleted all but the newest clip, then the index that needed it was dropped.
 *
 * <p>The damage was invisible in normal use. A merge correctly leaves the surviving section with
 * both sources' footage; the next restart deleted one of them; the catalogue simply showed one
 * clip, which is what a section used to have. This is what cost {@code KPWD/SH/1/3} the first
 * 7 860 m of its footage after it was merged.
 *
 * <p>The test re-runs the real schema migration against two real rows rather than asserting on
 * the absence of a line of code, so it fails if the dedupe returns under any name.
 */
@SpringBootTest
class VideoMultiClipSurvivesBootIT {

    private static final String SECTION = "__it_multiclip_section__";

    @Autowired JdbcTemplate jdbc;
    @Autowired VideoService video;

    @Test
    void twoClipsOnOneSectionSurviveTheSchemaMigration() {
        Integer period = jdbc.queryForObject(
                "SELECT id FROM survey_periods ORDER BY id LIMIT 1", Integer.class);
        assertNotNull(period, "no survey period to attach the fixture to");

        jdbc.update("DELETE FROM road_video WHERE section_label = ?", SECTION);
        try {
            jdbc.update("INSERT INTO road_video (section_label, video_file, direction, period_id, "
                      + "from_ch, to_ch) VALUES (?,?,?,?,?,?)",
                    SECTION, "first-half.webm", "forward", period, 0.0, 1000.0);
            jdbc.update("INSERT INTO road_video (section_label, video_file, direction, period_id, "
                      + "from_ch, to_ch) VALUES (?,?,?,?,?,?)",
                    SECTION, "second-half.webm", "forward", period, 1000.0, 2500.0);

            assertEquals(2, clips().size(), "fixture did not insert two clips");

            // Exactly what happens at startup.
            video.ensureSchema();

            List<Map<String, Object>> after = clips();
            assertEquals(2, after.size(),
                    "a restart deleted one of the section's clips — the multi-clip catalogue "
                  + "cannot survive a boot, so merged sections silently lose footage. Got: " + after);
            assertEquals(List.of("first-half.webm", "second-half.webm"),
                    after.stream().map(r -> String.valueOf(r.get("video_file"))).toList(),
                    "both clips must survive, in chainage order");
        } finally {
            jdbc.update("DELETE FROM road_video WHERE section_label = ?", SECTION);
        }
    }

    private List<Map<String, Object>> clips() {
        return jdbc.queryForList(
                "SELECT video_file, from_ch, to_ch FROM road_video WHERE section_label = ? "
              + "ORDER BY from_ch", SECTION);
    }
}
