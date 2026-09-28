package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Read-only report of what every inspector card / NSV source holds for a section label.
 *
 * <p>Writes nothing. It exists to answer "the card says no survey done — is that the database or
 * the browser?" without guessing, by listing the same tables the UI reads, per label:
 * {@code condition} and the derived {@code condition_segments} (Condition tab), {@code road_assets}
 * of type {@code fwd} and the derived {@code fwd_segments} (FWD tab), {@code road_assets} and {@code traffic_stations} (Survey
 * tab), and {@code road_video} (the "Play footage" button).
 *
 * <p>Labels come from {@code -Dsections=A,B}; with none given it reports on the result labels of
 * the most recent change in {@code section_change_log} that has not been undone.
 *
 * <p>Add {@code -Drebuild=true} to re-cut the derived layers first — the same work the "Rebuild
 * now" button does, which separates "the rows are missing" from "nothing has drawn them yet".
 *
 * <pre>./mvnw -Dtest=SectionDataDiagnosticIT -Dsections=KPWD/SH/1/2/X test</pre>
 */
@SpringBootTest
class SectionDataDiagnosticIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;
    @Autowired SegmentService segments;
    @Autowired FwdSegmentService fwdSegments;

    private long count(String table, String labelCol, String label) {
        try {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM \"" + table + "\" WHERE \"" + labelCol + "\" = ?",
                    Long.class, label);
        } catch (Exception e) {
            return -1;  // table or column absent in this database
        }
    }

    @Test
    void reportWhatEachInspectorSourceHolds() {
        List<String> labels = requestedSections();
        if (labels.isEmpty()) {
            System.out.println("[diag] no sections given and no recent change to report on");
            return;
        }

        /* Opt-in, off by default: re-cut the derived layers before reporting. This is the same
           work the "Rebuild now" button does, and it is the difference between "the rows are not
           there" and "the rows are there but nothing has drawn them yet". */
        if (Boolean.getBoolean("rebuild")) {
            System.out.println("[diag] rebuilding derived layers first…");
            System.out.println("[diag]   condition_segments: " + segments.buildSegments());
            System.out.println("[diag]   fwd_segments: " + fwdSegments.buildSegments());
        }

        String lbl = roadColumns.col(LayerAttributeCatalog.SECTION_LABEL).replace("\"", "");
        for (String label : labels) {
            System.out.println("\n================ " + label + " ================");

            List<Map<String, Object>> road = jdbc.queryForList(
                    "SELECT " + roadColumns.col("r", LayerAttributeCatalog.ROAD_NAME) + " AS name, "
                  + roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE) + " AS rs, "
                  + roadColumns.col("r", LayerAttributeCatalog.ROAD_END_CHAINAGE) + " AS re, "
                  + roadColumns.col("r", LayerAttributeCatalog.MEASURED_LENGTH) + " AS ml "
                  + "FROM roads r WHERE r.\"" + lbl + "\" = ?", label);
            if (road.isEmpty()) {
                System.out.println("  roads               : NOT PRESENT — this label does not exist");
                continue;
            }
            Map<String, Object> r = road.get(0);
            System.out.printf("  roads               : %s  road ch %s..%s, measured %s%n",
                    r.get("name"), r.get("rs"), r.get("re"), r.get("ml"));

            /* A child section with no centreline cannot be CUT, so every derived layer comes out
               empty however many times it is rebuilt — worth seeing before blaming the rebuild. */
            Map<String, Object> g = jdbc.queryForList(
                    "SELECT geom IS NULL AS missing, ST_GeometryType(geom) AS gtype, "
                  + "COALESCE(ST_Length(geom::geography), 0) AS glen, ST_IsValid(geom) AS valid "
                  + "FROM roads WHERE \"" + lbl + "\" = ?", label).get(0);
            System.out.printf("  geometry            : %s  %s  %.1f m on the ground  valid=%s%n",
                    Boolean.TRUE.equals(g.get("missing")) ? "MISSING" : "present",
                    g.get("gtype"), ((Number) g.get("glen")).doubleValue(), g.get("valid"));

            System.out.printf("  condition           : %d rows%n", count("condition", "section_label", label));
            Map<String, Object> cr = jdbc.queryForList(
                    "SELECT MIN(start_chainage) AS lo, MAX(end_chainage) AS hi, "
                  + "count(DISTINCT period_id) AS periods FROM condition WHERE section_label = ?",
                    label).get(0);
            System.out.printf("      chainage %s..%s over %s period(s)%n",
                    cr.get("lo"), cr.get("hi"), cr.get("periods"));
            System.out.printf("  condition_segments  : %d drawn   <- the Condition tab%n",
                    count("condition_segments", "section_label", label));
            /* Walk the build's own predicates, so a label that produces nothing says WHY. */
            System.out.printf("      pass agg filter   : %d rows (start/end not null, end > start)%n",
                    (long) jdbc.queryForObject(
                    "SELECT count(*) FROM condition WHERE section_label = ? AND start_chainage IS NOT NULL "
                  + "AND end_chainage IS NOT NULL AND end_chainage > start_chainage", Long.class, label));
            System.out.printf("      null xsp          : %d rows (a null lane kills the whole build)%n",
                    (long) jdbc.queryForObject(
                    "SELECT count(*) FROM condition WHERE section_label = ? AND xsp IS NULL",
                    Long.class, label));
            System.out.printf("      roads rows        : %d matching this label%n",
                    (long) jdbc.queryForObject(
                    "SELECT count(*) FROM roads WHERE \"" + lbl + "\" = ?", Long.class, label));
            System.out.printf("      linemerge type    : %s%n", jdbc.queryForObject(
                    "SELECT ST_GeometryType(ST_LineMerge(geom)) FROM roads WHERE \"" + lbl + "\" = ?",
                    String.class, label));
            System.out.printf("      measured_len expr : %s%n", jdbc.queryForObject(
                    "SELECT (" + roadColumns.lenExpr("r") + ")::text FROM roads r WHERE r.\"" + lbl + "\" = ?",
                    String.class, label));
            /* FWD's raw rows are road_assets of type 'fwd' — there is no separate fwd table. */
            System.out.printf("  road_assets (fwd)   : %d rows%n", (long) jdbc.queryForObject(
                    "SELECT count(*) FROM road_assets WHERE section_label = ? AND asset_type = 'fwd'",
                    Long.class, label));
            System.out.printf("  fwd_segments        : %d drawn   <- the FWD tab%n",
                    count("fwd_segments", "section_label", label));
            System.out.printf("  road_assets         : %d%n", count("road_assets", "section_label", label));
            System.out.printf("  traffic_stations    : %d%n", count("traffic_stations", "section", label));
            System.out.printf("  road_video          : %d clips  <- \"Play footage\"%n",
                    count("road_video", "section_label", label));

            for (Map<String, Object> v : jdbc.queryForList(
                    "SELECT video_file, from_ch, to_ch, file_from_ch, file_to_ch FROM road_video "
                  + "WHERE section_label = ? ORDER BY from_ch", label)) {
                System.out.printf("      clip %s  section %s..%s  file window %s..%s%n",
                        v.get("video_file"), v.get("from_ch"), v.get("to_ch"),
                        v.get("file_from_ch"), v.get("file_to_ch"));
            }
        }
        System.out.println();
    }

    /** Explicit -Dsections, else the result labels of the latest change still standing. */
    private List<String> requestedSections() {
        String given = System.getProperty("sections");
        if (given != null && !given.isBlank()) {
            return List.of(given.split("\\s*,\\s*"));
        }
        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT id, operation, result_labels FROM section_change_log "
              + "WHERE undone_at IS NULL ORDER BY id DESC LIMIT 1");
        if (last.isEmpty()) return List.of();
        System.out.println("[diag] latest standing change: #" + last.get(0).get("id")
                + " (" + last.get(0).get("operation") + ")");
        /* result_labels is jsonb; read the labels back out through the database rather than
           parsing the driver's representation. */
        return jdbc.queryForList(
                "SELECT jsonb_array_elements_text(result_labels) FROM section_change_log WHERE id = ?",
                String.class, last.get(0).get("id"));
    }
}
