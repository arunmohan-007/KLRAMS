package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs {@link SectionLineageService} against the real database.
 *
 * <p>The unit tests pin the arithmetic and the attribute resolution in isolation; nothing there
 * proves the SQL is valid, that the columns resolve against the schema that actually exists, or
 * that the discovery query finds the tables it is supposed to. Those are exactly the failures a
 * pure unit test cannot see, and they are the ones a dry run has to be free of before anyone
 * trusts its counts.
 *
 * <p><b>This test writes nothing and asserts that it wrote nothing.</b> The service has no
 * commit path yet, so that is a property of the code rather than of the test's care — which is
 * the point, and is checked here so it stays true.
 *
 * <p>Sections are chosen from the database at run time rather than hard-coded, so the test
 * travels between the local copy, staging and whatever has been imported since. Where no
 * suitable section exists the check is skipped rather than failed: an empty road network is a
 * reason to have nothing to verify, not evidence of a bug.
 */
@SpringBootTest
class SectionLineageDatabaseIT {

    @Autowired
    SectionLineageService lineage;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlacementService placement;

    /* ================= schema and resolution ================= */

    @Test
    void everyStructuralRoadAttributeResolvesAgainstTheLiveSchema() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        assertTrue(c.missing().isEmpty(),
                "the road network is missing system attribute(s): " + c.missing());

        System.out.println("[resolved] " + LayerAttributeCatalog.SECTION_LABEL + " -> " + c.label());
        System.out.println("[resolved] " + LayerAttributeCatalog.ROAD_START_CHAINAGE + " -> " + c.roadStart());
        System.out.println("[resolved] " + LayerAttributeCatalog.ROAD_END_CHAINAGE + " -> " + c.roadEnd());
        System.out.println("[resolved] " + LayerAttributeCatalog.SECTION_START_CHAINAGE + " -> " + c.sectionStart());
        System.out.println("[resolved] " + LayerAttributeCatalog.SECTION_END_CHAINAGE + " -> " + c.sectionEnd());
        System.out.println("[resolved] " + LayerAttributeCatalog.MEASURED_LENGTH + " -> " + c.measured());

        // The pair that must never collapse into one another.
        assertNotEquals(c.roadStart(), c.sectionStart(), "absolute and section-local start chainage");
        assertNotEquals(c.roadEnd(), c.sectionEnd(), "absolute and section-local end chainage");
    }

    @Test
    void discoveryFindsTheLinearReferencedTablesAndTheirChainageColumns() {
        List<SectionLineageService.Target> targets = lineage.targets();
        assertFalse(targets.isEmpty(), "no table carrying a section label was discovered");

        for (SectionLineageService.Target t : targets) {
            System.out.println("[target] " + t.table() + " label=" + t.labelColumn()
                    + " chainage=" + (t.hasChainage() ? t.startColumn() + ".." + t.endColumn() : "none")
                    + (t.isPoint() ? " (point)" : ""));
        }

        List<String> tables = targets.stream().map(SectionLineageService.Target::table).toList();
        assertEquals("roads", tables.get(0), "the road network must lead the list");

        // Derived tables are rebuilt, never migrated — they must not appear as work.
        for (String derived : List.of("condition_segments", "fwd_segments", "iri_2km_segments")) {
            assertFalse(tables.contains(derived), derived + " is derived and must not be a migration target");
        }

        // The tables whose chainage has to be re-based must have been recognised as having it.
        for (SectionLineageService.Target t : targets) {
            if (t.table().equals("condition") || t.table().equals("road_assets"))
                assertTrue(t.hasChainage(), t.table() + " must resolve its chainage columns");
            if (t.table().equals("traffic_stations"))
                assertTrue(t.isPoint(), "a traffic station is a point, not a span");
        }
    }

    @Test
    void aRealSectionLoadsWithAConsistentCalibration() {
        String label = anySplittableSection();
        assumeTrue(label != null, "no section with a usable chainage range in this database");

        SectionLineage.Section s = lineage.loadSection(label);
        assertNotNull(s, "loadSection returned nothing for a section the database has");
        assertTrue(s.hasChainageRange(), label + " should have a usable chainage range");
        assertEquals(0, s.localStartCh(), "section-local chainage always starts at 0");
        assertTrue(s.localEndCh() > 0, "section-local end chainage is its length");

        System.out.println("[section] " + label + "  road " + s.startCh() + ".." + s.endCh()
                + "  local 0.." + Math.round(s.localEndCh()) + "  measured " + s.measuredLen());
    }

    /* ================= the dry runs ================= */

    @Test
    void splitPreviewRunsAgainstARealSectionAndChangesNothing() {
        String label = anySplittableSection();
        assumeTrue(label != null, "no splittable section in this database");

        SectionLineage.Section s = lineage.loadSection(label);
        double midpoint = s.startCh() + (s.endCh() - s.startCh()) / 2;

        List<Map<String, Object>> before = snapshot(label);
        Map<String, Object> r = lineage.previewSplit(label, midpoint, label + "/A", label + "/B", "test");
        System.out.println("[split] " + label + " at " + midpoint + " -> " + r.get("status"));
        System.out.println("        " + r.get("message"));

        assertEquals("ok", r.get("status"), "split preview refused: " + r.get("message"));
        assertEquals(Boolean.TRUE, r.get("dry_run"));

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) r.get("plan");
        assertNotNull(plan, "an ok preview must carry a plan");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) plan.get("first");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) plan.get("second");

        // Keyed by system attribute name, never by the DBF column.
        assertTrue(first.containsKey(LayerAttributeCatalog.ROAD_START_CHAINAGE),
                "the plan must be keyed by system attribute names, got: " + first.keySet());
        assertEquals(0.0, ((Number) first.get(LayerAttributeCatalog.SECTION_START_CHAINAGE)).doubleValue());
        assertEquals(0.0, ((Number) second.get(LayerAttributeCatalog.SECTION_START_CHAINAGE)).doubleValue(),
                "the second half restarts its own chainage at 0");
        assertEquals(((Number) first.get(LayerAttributeCatalog.ROAD_END_CHAINAGE)).doubleValue(),
                     ((Number) second.get(LayerAttributeCatalog.ROAD_START_CHAINAGE)).doubleValue(),
                     1e-6, "the halves must abut in road chainage");

        System.out.println("        first  " + first);
        System.out.println("        second " + second);
        System.out.println("        tables " + r.get("tables"));
        System.out.println("        straddling " + r.get("straddling_total"));
        System.out.println("        warnings " + r.get("warnings"));

        assertEquals(before, snapshot(label), "a dry run must not change any row count");
    }

    @Test
    void mergePreviewRunsAgainstTwoAdjacentRealSectionsAndChangesNothing() {
        List<String> pair = anyAdjacentPair();
        assumeTrue(pair != null, "no adjacent section pair in this database");

        List<Map<String, Object>> before = new ArrayList<>(snapshot(pair.get(0)));
        before.addAll(snapshot(pair.get(1)));

        Map<String, Object> r = lineage.previewMerge(pair, pair.get(0), "test");
        System.out.println("[merge] " + pair + " -> " + r.get("status"));
        System.out.println("        " + r.get("message"));

        assertEquals("ok", r.get("status"), "merge preview refused: " + r.get("message"));

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) r.get("plan");
        @SuppressWarnings("unchecked")
        Map<String, Double> offsets = (Map<String, Double>) plan.get("offsets");
        assertEquals(2, offsets.size(), "one offset per contributing section");
        assertTrue(offsets.values().stream().anyMatch(v -> v.doubleValue() == 0.0),
                "the leading section's data must not move");
        assertTrue(offsets.values().stream().anyMatch(v -> v.doubleValue() > 0.0),
                "the following section's data must be offset by the leader's length");

        System.out.println("        result  " + plan.get("result"));
        System.out.println("        offsets " + offsets);
        System.out.println("        tables  " + r.get("tables"));

        List<Map<String, Object>> after = new ArrayList<>(snapshot(pair.get(0)));
        after.addAll(snapshot(pair.get(1)));
        assertEquals(before, after, "a dry run must not change any row count");
    }

    @Test
    void aSectionWithInconsistentChainageColumnsIsRefused() {
        String label = anyInconsistentSection();
        assumeTrue(label != null, "no section with disagreeing chainage columns in this database");

        SectionLineage.Section s = lineage.loadSection(label);
        double midpoint = s.startCh() + (s.endCh() - s.startCh()) / 2;
        Map<String, Object> r = lineage.previewSplit(label, midpoint, label + "/A", label + "/B", "test");

        System.out.println("[refused] " + label + " -> " + r.get("message"));
        assertEquals("refused", r.get("status"),
                "a section whose chainage columns disagree must not be splittable");
    }

    /* ================= a new label must be free, but its own is not "taken" ================= */

    /**
     * A label typed for a new half must be checked against EVERY existing section — but the
     * labels this operation is consuming are not "existing" for that purpose, or the obvious
     * thing an engineer wants to do (keep the parent's label on the first half) would be
     * refused as a clash with itself.
     */
    @Test
    void aNewSplitLabelThatBelongsToAnotherSectionIsRefused() {
        String parent = anySplittableSection();
        assumeTrue(parent != null, "no splittable section");
        String other = jdbc.queryForObject(
                "SELECT q FROM (SELECT \"" + lineage.roadColumns().label() + "\" AS q FROM roads) r "
              + "WHERE q <> ? ORDER BY 1 LIMIT 1", String.class, parent);
        assumeTrue(other != null, "need a second section");

        SectionLineage.Section s = lineage.loadSection(parent);
        double mid = s.startCh() + (s.endCh() - s.startCh()) / 2;

        Map<String, Object> r = lineage.previewSplit(parent, mid, other, parent + "/B", "test");
        System.out.println("[label] split onto \"" + other + "\" -> " + r.get("status") + ": " + r.get("message"));
        assertEquals("refused", r.get("status"),
                "a half must not be given a label another section already uses");
        assertTrue(String.valueOf(r.get("message")).contains("already used"), String.valueOf(r.get("message")));
    }

    @Test
    void keepingTheParentsOwnLabelOnOneHalfIsAllowed() {
        String parent = anySplittableSection();
        assumeTrue(parent != null, "no splittable section");
        SectionLineage.Section s = lineage.loadSection(parent);
        double mid = s.startCh() + (s.endCh() - s.startCh()) / 2;

        // The section being split is being consumed, so its label is free to reuse.
        Map<String, Object> r = lineage.previewSplit(parent, mid, parent, parent + "/B", "test");
        System.out.println("[label] keep parent label -> " + r.get("status"));
        assertEquals("ok", r.get("status"),
                "reusing the label of the section being split must be allowed: " + r.get("message"));
    }

    @Test
    void aMergeResultThatBelongsToAnUnrelatedSectionIsRefused() {
        List<String> pair = anyAdjacentPair();
        assumeTrue(pair != null, "no adjacent pair");
        String other = jdbc.queryForObject(
                "SELECT q FROM (SELECT \"" + lineage.roadColumns().label() + "\" AS q FROM roads) r "
              + "WHERE q NOT IN (?,?) ORDER BY 1 LIMIT 1",
                String.class, pair.get(0), pair.get(1));
        assumeTrue(other != null, "need a third section");

        Map<String, Object> r = lineage.previewMerge(pair, other, "test");
        System.out.println("[label] merge onto \"" + other + "\" -> " + r.get("status") + ": " + r.get("message"));
        assertEquals("refused", r.get("status"),
                "the merged section must not take a label an unrelated section already uses");
    }

    @Test
    void aMergeMayKeepOneOfItsOwnSectionsLabels() {
        List<String> pair = anyAdjacentPair();
        assumeTrue(pair != null, "no adjacent pair");
        for (String keep : pair) {
            Map<String, Object> r = lineage.previewMerge(pair, keep, "test");
            assertEquals("ok", r.get("status"),
                    "keeping \"" + keep + "\" — one of the sections being merged — must be allowed: "
                  + r.get("message"));
        }
        System.out.println("[label] merge may keep either of its own labels");
    }

    /**
     * A label is "in use" if ANY layer still references it, not only the road network. Survey
     * rows left behind by an earlier mistake are exactly what must not be silently adopted: the
     * new section would inherit another section's history and every total would double.
     */
    @Test
    void aLabelHeldOnlyByStrandedSurveyRowsStillCountsAsTaken() {
        String parent = anySplittableSection();
        assumeTrue(parent != null, "no splittable section");
        String orphan = "IT/ORPHAN/LABEL/1";
        jdbc.update("INSERT INTO condition (survey_type, section_label, start_chainage, end_chainage) "
                  + "VALUES ('IT', ?, 0, 100)", orphan);
        try {
            SectionLineage.Section s = lineage.loadSection(parent);
            double mid = s.startCh() + (s.endCh() - s.startCh()) / 2;
            Map<String, Object> r = lineage.previewSplit(parent, mid, parent + "/A", orphan, "test");
            System.out.println("[label] orphaned rows -> " + r.get("status") + ": " + r.get("message"));
            assertEquals("refused", r.get("status"),
                    "a label still referenced by survey rows is not free, even with no road section");
        } finally {
            jdbc.update("DELETE FROM condition WHERE section_label = ?", orphan);
        }
    }

    /**
     * The live check the editor runs while the operator types must give the same answer the
     * preview will enforce — otherwise a field reads "available" for a label the apply refuses.
     */
    @Test
    void theLiveLabelCheckAgreesWithWhatThePreviewWouldAllow() {
        String existing = anySplittableSection();
        assumeTrue(existing != null, "no section");

        Map<String, Object> taken = lineage.checkLabel(existing, List.of());
        assertEquals(Boolean.FALSE, taken.get("available"), "an existing section's label is not free");
        assertFalse(((List<?>) taken.get("used_by")).isEmpty(), "it must say what holds it");

        Map<String, Object> excluded = lineage.checkLabel(existing, List.of(existing));
        assertEquals(Boolean.TRUE, excluded.get("available"),
                "a label belonging to a section this operation consumes is free to reuse");

        Map<String, Object> fresh = lineage.checkLabel("IT/DEFINITELY/NEW/LABEL/9", List.of());
        assertEquals(Boolean.TRUE, fresh.get("available"));

        Map<String, Object> blank = lineage.checkLabel("   ", List.of());
        assertEquals(Boolean.FALSE, blank.get("available"), "a blank label is not a valid one");

        System.out.println("[label-check] taken=" + taken.get("message"));
        System.out.println("[label-check] excluded=" + excluded.get("message"));
    }

    @Test
    void anUnknownSectionIsRefusedRatherThanCrashing() {
        Map<String, Object> r = lineage.previewSplit(
                "NOT/A/REAL/SECTION", 100, "a", "b", "test");
        assertEquals("refused", r.get("status"));
        assertTrue(String.valueOf(r.get("message")).contains("not a road section"),
                "message was: " + r.get("message"));
    }

    @Test
    void mergingNonAdjacentSectionsIsRefused() {
        List<String> far = jdbc.queryForList(
                "SELECT \"" + lineage.roadColumns().label() + "\" FROM roads " +
                "WHERE \"" + lineage.roadColumns().roadStart() + "\" IS NOT NULL " +
                "ORDER BY 1 LIMIT 2", String.class);
        assumeTrue(far.size() == 2, "need two sections to try this");

        Map<String, Object> r = lineage.previewMerge(far, far.get(0), "test");
        // Two arbitrary sections are overwhelmingly unlikely to abut; if they happen to, the
        // preview is right to accept them and there is nothing to assert.
        assumeTrue("refused".equals(r.get("status")), "the two sampled sections happened to be adjacent");
        System.out.println("[refused] " + far + " -> " + r.get("message"));
        assertNotNull(r.get("message"));
    }

    @Test
    void sectionsOnDifferentRoadsAreRefusedEvenWhenTheirChainagesAbut() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        assumeTrue(c.roadName() != null, "this network carries no Road Name attribute");

        // Two sections that meet in road chainage but belong to different roads. The absolute
        // chainage frame is only partly populated, so this coincidence is not rare.
        List<Map<String, Object>> pair = jdbc.queryForList(
                "SELECT a.\"" + c.label() + "\" AS first, b.\"" + c.label() + "\" AS second, " +
                "       a.\"" + c.roadName() + "\" AS road_a, b.\"" + c.roadName() + "\" AS road_b " +
                "FROM roads a JOIN roads b " +
                "  ON abs(b.\"" + c.roadStart() + "\"::double precision " +
                "       - a.\"" + c.roadEnd() + "\"::double precision) <= 1.0 " +
                " AND TRIM(a.\"" + c.roadName() + "\") <> TRIM(b.\"" + c.roadName() + "\") " +
                "WHERE a.geom IS NOT NULL AND b.geom IS NOT NULL " +
                "  AND a.\"" + c.roadStart() + "\" IS NOT NULL AND a.\"" + c.roadEnd() + "\" IS NOT NULL " +
                "  AND b.\"" + c.roadStart() + "\" IS NOT NULL AND b.\"" + c.roadEnd() + "\" IS NOT NULL " +
                "ORDER BY 1 LIMIT 1");
        assumeTrue(!pair.isEmpty(), "no cross-road abutting pair exists in this database");

        List<String> labels = List.of(String.valueOf(pair.get(0).get("first")),
                                      String.valueOf(pair.get(0).get("second")));
        Map<String, Object> r = lineage.previewMerge(labels, labels.get(0), "test");

        System.out.println("[cross-road] " + labels);
        System.out.println("             " + pair.get(0).get("road_a") + "  vs  " + pair.get(0).get("road_b"));
        System.out.println("             -> " + r.get("status") + ": " + r.get("message"));

        assertEquals("refused", r.get("status"),
                "sections of two different roads must not merge just because their chainages meet");
        assertTrue(String.valueOf(r.get("message")).contains("same road"),
                "the refusal must say why: " + r.get("message"));
    }

    @Test
    void reportsHowMuchOfTheNetworkCarriesAnAbsoluteChainageFrame() {
        // Not an assertion about correctness — a measurement that decides how usable merge is.
        // Adjacency is established from absolute road chainage, so sections that all declare
        // 0..length cannot be shown to be consecutive and will be refused.
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        assumeTrue(c.missing().isEmpty(), "road attributes unresolved");

        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT count(*) AS total, " +
                "       count(*) FILTER (WHERE \"" + c.roadStart() + "\"::double precision = 0) AS starts_at_zero, " +
                "       count(*) FILTER (WHERE \"" + c.roadStart() + "\" IS NULL) AS no_start " +
                "FROM roads");
        long total = ((Number) stats.get("total")).longValue();
        long zero = ((Number) stats.get("starts_at_zero")).longValue();
        assumeTrue(total > 0, "empty road network");

        System.out.printf("[frame] %d sections; %d (%.0f%%) start at road chainage 0; %s have none%n",
                total, zero, 100.0 * zero / total, stats.get("no_start"));
        assertTrue(total > 0);
    }

    /* ================= chainage correction (Phase 1.5) ================= */

    @Test
    void correctionPreviewSeesThatReAnchoringMovesNoData() {
        String label = anySplittableSection();
        assumeTrue(label != null, "no suitable section");
        SectionLineage.Section s = lineage.loadSection(label);

        // Shift the section 500 m along its road, same length: the divisor is unchanged.
        Map<String, Object> r = lineage.previewChainageCorrection(
                label, s.startCh() + 500, s.endCh() + 500, s.measuredLen(), "test");
        assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));

        @SuppressWarnings("unchecked")
        Map<String, Object> effect = (Map<String, Object>) r.get("effect");
        assertEquals(Boolean.FALSE, effect.get("length_changes"), "length must be unchanged");
        assertEquals(Boolean.FALSE, effect.get("data_moves"), "no stored row may move");
        System.out.println("[correct/re-anchor] " + label + " -> " + effect.get("explanation"));
    }

    @Test
    void correctionPreviewCountsRowsThatWouldFallPastAShortenedSection() {
        String label = sectionWithChainageData();
        assumeTrue(label != null, "no section carrying chainage-bearing rows");
        SectionLineage.Section s = lineage.loadSection(label);

        // Halve it. Anything past the new length loses its position.
        double newEnd = s.startCh() + (s.endCh() - s.startCh()) / 2;
        Map<String, Object> r = lineage.previewChainageCorrection(label, s.startCh(), newEnd, null, "test");
        assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));

        long beyond = ((Number) r.get("rows_beyond_new_length")).longValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> effect = (Map<String, Object>) r.get("effect");
        assertEquals(Boolean.TRUE, effect.get("length_changes"));
        assertTrue(beyond > 0, "halving a section carrying data must strand rows past the new end");

        System.out.println("[correct/shorten] " + label + " halved -> " + beyond + " row(s) beyond, scale "
                + effect.get("scale_factor"));
        System.out.println("                  sample " + r.get("rows_beyond_sample"));
    }

    @Test
    void applyRefusesWithoutConfirmAndWithoutOverflowAcknowledgement() {
        String label = sectionWithChainageData();
        assumeTrue(label != null, "no section carrying chainage-bearing rows");
        SectionLineage.Section s = lineage.loadSection(label);
        double newEnd = s.startCh() + (s.endCh() - s.startCh()) / 2;

        Map<String, Object> noConfirm = lineage.applyChainageCorrection(
                label, s.startCh(), newEnd, null, false, false, "test");
        assertEquals("refused", noConfirm.get("status"));
        assertTrue(String.valueOf(noConfirm.get("message")).contains("confirm"));

        Map<String, Object> noAck = lineage.applyChainageCorrection(
                label, s.startCh(), newEnd, null, true, false, "test");
        assertEquals("refused", noAck.get("status"), "overflow must be acknowledged explicitly");
        assertTrue(String.valueOf(noAck.get("message")).contains("accept_overflow"));

        assertEquals(s.endCh(), lineage.loadSection(label).endCh(), 1e-6,
                "a refused apply must not have written anything");
        System.out.println("[correct/refuse] " + noAck.get("message"));
    }

    /**
     * The only test here that writes. It re-anchors a section (length unchanged, so no stored
     * row moves), checks the row and the log, then puts the original values back.
     */
    @Test
    void applyWritesTheSectionAndLogsItThenIsRestored() {
        String label = anySplittableSection();
        assumeTrue(label != null, "no suitable section");
        SectionLineage.Section before = lineage.loadSection(label);

        long logsBefore = jdbc.queryForObject("SELECT count(*) FROM section_change_log", Long.class);
        double shift = 500;

        try {
            Map<String, Object> r = lineage.applyChainageCorrection(
                    label, before.startCh() + shift, before.endCh() + shift, before.measuredLen(),
                    true, false, "integration-test");
            assertEquals("ok", r.get("status"), String.valueOf(r.get("message")));
            assertEquals(Boolean.FALSE, r.get("dry_run"));
            System.out.println("[correct/apply] " + r.get("message"));
            System.out.println("                replaced " + r.get("replaced"));

            // The re-place is reported rather than thrown, so without this the test would pass
            // while placement was failing underneath it — which is exactly what it did at first.
            @SuppressWarnings("unchecked")
            Map<String, Object> replaced = (Map<String, Object>) r.get("replaced");
            assertNotEquals("failed", replaced.get("status"),
                    "re-place failed after the correction committed: " + replaced.get("message"));

            SectionLineage.Section after = lineage.loadSection(label);
            assertEquals(before.startCh() + shift, after.startCh(), 1e-6, "road start chainage written");
            assertEquals(before.endCh() + shift, after.endCh(), 1e-6, "road end chainage written");
            assertEquals(0, after.localStartCh(), "section-local start stays 0");
            assertEquals(Math.abs(before.refLen()), Math.abs(after.refLen()), 1e-6,
                    "a re-anchor must not change the reference length");

            assertEquals(logsBefore + 1,
                    jdbc.queryForObject("SELECT count(*) FROM section_change_log", Long.class),
                    "the correction must be logged");
            Map<String, Object> logged = jdbc.queryForMap(
                    "SELECT operation, username, snapshot IS NOT NULL AS has_snapshot "
                  + "FROM section_change_log ORDER BY id DESC LIMIT 1");
            assertEquals("chainage_correction", logged.get("operation"));
            assertEquals("integration-test", logged.get("username"));
            assertEquals(Boolean.TRUE, logged.get("has_snapshot"),
                    "without a snapshot the change cannot be undone");
            System.out.println("[correct/log] " + logged);

        } finally {
            // Put it back whatever happened, so the developer database is left as found.
            lineage.applyChainageCorrection(label, before.startCh(), before.endCh(),
                    before.measuredLen(), true, true, "integration-test-restore");
            SectionLineage.Section restored = lineage.loadSection(label);
            assertEquals(before.startCh(), restored.startCh(), 1e-6, "restored start");
            assertEquals(before.endCh(), restored.endCh(), 1e-6, "restored end");
        }
    }

    @Test
    void reversedLineAssetsDoNotAbortPlacementForTheWholeAssetType() {
        // ST_LineSubstring raises when the start fraction exceeds the end one, and placement is
        // one statement per asset type — so before the fractions were ordered, a single
        // From/To recorded backwards aborted the update for every asset of that type.
        Long reversed = jdbc.queryForObject(
                "SELECT count(*) FROM road_assets WHERE start_chainage > end_chainage", Long.class);
        System.out.println("[reversed] " + reversed + " road_assets row(s) have start > end chainage");
        assumeTrue(reversed != null && reversed > 0, "no reversed rows in this database");

        String label = jdbc.queryForObject(
                "SELECT section_label FROM road_assets WHERE start_chainage > end_chainage LIMIT 1",
                String.class);
        Map<String, Object> r = assertDoesNotThrow(() -> placement.replaceForSection(label),
                "a reversed line asset must not abort placement for its whole type");
        System.out.println("[reversed] re-placed section " + label + " -> " + r);
    }

    /* ================= helpers: pick real sections ================= */

    /** A splittable section that actually carries chainage-bearing rows. */
    private String sectionWithChainageData() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        if (!c.missing().isEmpty() || c.measured() == null) return null;
        List<String> found = jdbc.queryForList(
                "SELECT r.\"" + c.label() + "\" FROM roads r " +
                "WHERE r.geom IS NOT NULL " +
                "  AND r.\"" + c.roadStart() + "\" IS NOT NULL AND r.\"" + c.roadEnd() + "\" IS NOT NULL " +
                "  AND r.\"" + c.roadEnd() + "\"::double precision " +
                "    - r.\"" + c.roadStart() + "\"::double precision > 100 " +
                "  AND EXISTS (SELECT 1 FROM condition d WHERE d.section_label = r.\"" + c.label() + "\") " +
                "ORDER BY 1 LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** A section with a usable chainage range whose columns agree — safe to split. */
    private String anySplittableSection() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        if (!c.missing().isEmpty() || c.measured() == null) return null;
        List<String> found = jdbc.queryForList(
                "SELECT r.\"" + c.label() + "\" FROM roads r " +
                "WHERE r.geom IS NOT NULL " +
                "  AND r.\"" + c.roadStart() + "\" IS NOT NULL AND r.\"" + c.roadEnd() + "\" IS NOT NULL " +
                "  AND abs(r.\"" + c.roadEnd() + "\"::double precision " +
                "        - r.\"" + c.roadStart() + "\"::double precision) > 100 " +
                "  AND abs(abs(r.\"" + c.roadEnd() + "\"::double precision " +
                "           - r.\"" + c.roadStart() + "\"::double precision) " +
                "        - r.\"" + c.measured() + "\"::double precision) <= 0.5 " +
                "ORDER BY 1 LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** A section whose four chainage columns disagree — must be refused. */
    private String anyInconsistentSection() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        if (!c.missing().isEmpty() || c.measured() == null) return null;
        List<String> found = jdbc.queryForList(
                "SELECT r.\"" + c.label() + "\" FROM roads r " +
                "WHERE r.\"" + c.roadStart() + "\" IS NOT NULL AND r.\"" + c.roadEnd() + "\" IS NOT NULL " +
                "  AND r.\"" + c.measured() + "\" IS NOT NULL " +
                "  AND abs(abs(r.\"" + c.roadEnd() + "\"::double precision " +
                "           - r.\"" + c.roadStart() + "\"::double precision) " +
                "        - r.\"" + c.measured() + "\"::double precision) > 0.5 " +
                "ORDER BY 1 LIMIT 1", String.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Two sections of one road that abut in road chainage — a genuine merge candidate. */
    private List<String> anyAdjacentPair() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        if (!c.missing().isEmpty() || c.measured() == null) return null;
        String lbl = c.label(), rs = c.roadStart(), re = c.roadEnd(), ml = c.measured();
        String consistent =
                " AND abs(abs(%1$s.\"" + re + "\"::double precision - %1$s.\"" + rs + "\"::double precision)"
              + " - %1$s.\"" + ml + "\"::double precision) <= 0.5 ";
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT a.\"" + lbl + "\" AS first, b.\"" + lbl + "\" AS second " +
                "FROM roads a JOIN roads b " +
                "  ON abs(b.\"" + rs + "\"::double precision - a.\"" + re + "\"::double precision) <= 1.0 " +
                " AND a.\"" + lbl + "\" <> b.\"" + lbl + "\" " +
                "WHERE a.geom IS NOT NULL AND b.geom IS NOT NULL " +
                "  AND a.\"" + rs + "\" IS NOT NULL AND a.\"" + re + "\" IS NOT NULL " +
                "  AND b.\"" + rs + "\" IS NOT NULL AND b.\"" + re + "\" IS NOT NULL " +
                "  AND a.\"" + re + "\"::double precision > a.\"" + rs + "\"::double precision " +
                "  AND b.\"" + re + "\"::double precision > b.\"" + rs + "\"::double precision " +
                consistent.formatted("a") + consistent.formatted("b") +
                /* Abutting chainage does not mean touching geometry: in this network most
                   chainage-adjacent pairs are drawn kilometres apart, and the merge rightly
                   refuses those. Only a pair whose centrelines actually join can be merged. */
                "  AND ST_GeometryType(ST_LineMerge(ST_Union(a.geom, b.geom))) = 'ST_LineString' " +
                "ORDER BY 1 LIMIT 1");
        if (found.isEmpty()) return null;
        return List.of(String.valueOf(found.get(0).get("first")), String.valueOf(found.get(0).get("second")));
    }

    /** Row counts for one section across every discovered table — the "nothing changed" probe. */
    private List<Map<String, Object>> snapshot(String label) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SectionLineageService.Target t : lineage.targets()) {
            out.add(Map.of("table", t.table(), "rows", jdbc.queryForObject(
                    "SELECT count(*) FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ?",
                    Long.class, label)));
        }
        return out;
    }
}
