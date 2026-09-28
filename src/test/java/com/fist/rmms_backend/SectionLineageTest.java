package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The split/merge arithmetic — the one part of the section lineage module that, if it is
 * wrong, is wrong SILENTLY.
 *
 * <p>Every other failure in this feature announces itself: a bad label raises, a
 * non-contiguous merge refuses, a broken geometry draws visibly wrong. A chainage that is
 * re-based by the wrong offset does none of that. The rows are present, the map draws them,
 * and every asset on the section is simply somewhere else — proportionally, so it still looks
 * like a road with bridges on it.
 *
 * <p>So these tests are written against the numbers an engineer would check by hand, not
 * against the implementation: a culvert 200 m into the second of two merged sections is
 * 1200 m into the merged one, and splitting that merged section back at the join puts it at
 * 200 m again.
 */
class SectionLineageTest {

    /* A plain 1000 m section running 0..1000 of its road. */
    private static final SectionLineage.Section A =
            new SectionLineage.Section("KPWD/MDR/5010/1", 0.0, 1000.0, 1000.0, 1000.0);
    /* The 800 m section that continues it, 1000..1800. */
    private static final SectionLineage.Section B =
            new SectionLineage.Section("KPWD/MDR/5010/2", 1000.0, 1800.0, 800.0, 800.0);

    private static void near(double expected, double actual, String what) {
        assertEquals(expected, actual, 1e-6, what);
    }

    private static String refused(Runnable r) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        return e.getMessage();
    }

    /* ===================== merge ===================== */

    @Test
    void mergeOffsetsTheSecondSectionByTheFirstsLength() {
        SectionLineage.MergePlan p = SectionLineage.planMerge(List.of(A, B), "KPWD/MDR/5010/1");

        near(0, p.offsets().get(A.label()), "the first section's data must not move");
        near(1000, p.offsets().get(B.label()), "the second section's data must shift by A's length");

        // The check an engineer would make: a culvert 200 m into B is 1200 m into the merge.
        SectionLineage.Placed culvert = SectionLineage.merged(p, B.label(), 200, 200);
        near(1200, culvert.start(), "culvert at 200 m on B");
        near(1200, culvert.end(), "a point row keeps zero length");

        // And a bridge stretch carries both ends.
        SectionLineage.Placed bridge = SectionLineage.merged(p, B.label(), 300, 460);
        near(1300, bridge.start(), "bridge start");
        near(1460, bridge.end(), "bridge end");
    }

    @Test
    void mergedSectionSpansBothAndKeepsTheCalibrationConsistent() {
        SectionLineage.Section r =
                SectionLineage.planMerge(List.of(A, B), "KPWD/MDR/5010/1").result();

        near(0, r.startCh(), "merged start chainage");
        near(1800, r.endCh(), "merged end chainage");
        near(1800, r.measuredLen(), "measured lengths add");
        // The one invariant that makes every placement query keep working: the reference
        // length the merged section divides by is the sum of the lengths its data was
        // re-based against. If these ever disagree, everything shifts proportionally.
        near(A.refLen() + B.refLen(), r.refLen(), "merged reference length");
    }

    @Test
    void mergeSortsItsInputsRatherThanTrustingTheOrderGiven() {
        // B first — the order a user might click them in. Offsets must not follow that.
        SectionLineage.MergePlan p = SectionLineage.planMerge(List.of(B, A), "M");
        near(0, p.offsets().get(A.label()), "A still leads");
        near(1000, p.offsets().get(B.label()), "B still follows A");
        assertEquals(A.label(), p.sources().get(0).label(), "sources come back in chainage order");
    }

    @Test
    void mergeRequiresTheChainagesToMeetExactly() {
        /* Road End Chainage and Road Start Chainage are operator input, not two independent
           field measurements, so the same join is the same typed figure on both sections.
           40 cm is a data error to be corrected, not noise to be tolerated: it would be absorbed
           into the merged length and stretch every re-based chainage by it. */
        SectionLineage.Section b = new SectionLineage.Section("B", 1000.4, 1800.0, 799.6, 799.6);
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, b), "M")).contains("not adjacent"),
                "a 0.4 m discrepancy at the join must be refused, not absorbed");

        // Exactly equal is the only accepted case.
        SectionLineage.Section exact = new SectionLineage.Section("B", 1000.0, 1800.0, 800.0, 800.0);
        assertDoesNotThrow(() -> SectionLineage.planMerge(List.of(A, exact), "M"));
    }

    @Test
    void mergeRefusesAGap() {
        SectionLineage.Section far = new SectionLineage.Section("B", 1200.0, 1800.0, 600.0, 600.0);
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, far), "M")).contains("gap"),
                "a 200 m gap must be named as one");
    }

    @Test
    void mergeRefusesAnOverlap() {
        SectionLineage.Section over = new SectionLineage.Section("B", 700.0, 1800.0, 1100.0, 1100.0);
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, over), "M")).contains("overlap"),
                "overlapping sections are not consecutive stretches");
    }

    @Test
    void mergeRefusesAReversedSection() {
        SectionLineage.Section rev = new SectionLineage.Section("B", 1800.0, 1000.0, 800.0, 800.0);
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, rev), "M")).contains("direction"),
                "a section digitised backwards would walk its data the wrong way");
    }

    @Test
    void mergeRefusesASectionWithNoChainageRange() {
        SectionLineage.Section noCh = new SectionLineage.Section("B", null, null, 800.0, 800.0);
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, noCh), "M")).contains("chainage"),
                "without a chainage range adjacency cannot be established");
    }

    @Test
    void mergeRefusesFewerThanTwoSectionsAndDuplicates() {
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A), "M")).contains("two"));
        assertTrue(refused(() -> SectionLineage.planMerge(List.of(A, A), "M")).contains("twice"));
    }

    /* ===================== split ===================== */

    @Test
    void splitIsTheExactInverseOfMerge() {
        // Merge A+B, then split the result back at the join: everything must land where it began.
        SectionLineage.MergePlan m = SectionLineage.planMerge(List.of(A, B), "M");
        SectionLineage.Placed onMerged = SectionLineage.merged(m, B.label(), 200, 200);

        SectionLineage.SplitPlan s = SectionLineage.planSplit(m.result(), 1000.0, A.label(), B.label());
        SectionLineage.Placed back = SectionLineage.assignSpan(s, onMerged.start(), onMerged.end());

        assertEquals(B.label(), back.label(), "the culvert belongs to the second section again");
        near(200, back.start(), "and is back at 200 m into it");
    }

    @Test
    void splitChildrenAbutAndSumToTheParent() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        near(1000, p.localSplit(), "local offset of the cut");
        near(1000.0 / 1800.0, p.fraction(), "the fraction ST_LineSubstring will cut at");

        near(0, p.first().startCh(), "first child start");
        near(1000, p.first().endCh(), "first child end");
        near(1000, p.second().startCh(), "second child starts where the first ended");
        near(1800, p.second().endCh(), "second child end");
        near(parent.refLen(), p.first().refLen() + p.second().refLen(), "lengths sum to the parent's");
    }

    @Test
    void splitDividesMeasuredLengthInTheParentsOwnProportion() {
        // A section whose measured length already disagrees with its chainage span: 1800 m of
        // chainage, 1900 m measured on the ground. The discrepancy must be shared, not
        // silently corrected onto one half.
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1900.0, 1850.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 900.0, "P/1", "P/2");

        near(950, p.first().measuredLen(), "half the measured length");
        near(950, p.second().measuredLen(), "the other half");
        near(1900, p.first().measuredLen() + p.second().measuredLen(), "and they still total");
    }

    @Test
    void splitRefusesACutAtOrOutsideTheEnds() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        for (double ch : new double[]{0, 1800, -50, 2500}) {
            assertTrue(refused(() -> SectionLineage.planSplit(parent, ch, "P/1", "P/2")).contains("inside"),
                    "chainage " + ch + " must be refused: it would leave an empty half");
        }
    }

    @Test
    void splitRefusesTwoIdenticalLabels() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        assertTrue(refused(() -> SectionLineage.planSplit(parent, 900.0, "P/1", "P/1")).contains("same label"));
    }

    /* ===================== moving rows across a split ===================== */

    @Test
    void anIntervalRowStraddlingTheCutIsDividedInTwo() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        // A condition row measured over 900..1100 — the decision recorded for `condition`.
        List<SectionLineage.Placed> parts = SectionLineage.divideSpan(p, 900, 1100);

        assertEquals(2, parts.size(), "it spans the cut, so it becomes two rows");
        assertEquals("P/1", parts.get(0).label());
        near(900, parts.get(0).start(), "first part keeps its start");
        near(1000, parts.get(0).end(), "first part ends at the cut");
        assertEquals("P/2", parts.get(1).label());
        near(0, parts.get(1).start(), "second part starts at the new section's origin");
        near(100, parts.get(1).end(), "second part is re-based");

        // No length is created or lost by dividing.
        double before = 1100 - 900;
        double after = (parts.get(0).end() - parts.get(0).start())
                     + (parts.get(1).end() - parts.get(1).start());
        near(before, after, "the divided row still covers 200 m of road");
    }

    @Test
    void anIntervalRowOnEitherSideIsMovedWhole() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        List<SectionLineage.Placed> before = SectionLineage.divideSpan(p, 100, 300);
        assertEquals(1, before.size());
        assertEquals("P/1", before.get(0).label());
        near(100, before.get(0).start(), "a row before the cut does not move at all");

        List<SectionLineage.Placed> after = SectionLineage.divideSpan(p, 1200, 1400);
        assertEquals(1, after.size());
        assertEquals("P/2", after.get(0).label());
        near(200, after.get(0).start(), "a row after the cut is re-based");
        near(400, after.get(0).end(), "both ends");
    }

    @Test
    void aBridgeStraddlingTheCutIsNotDivided() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        // The same 900..1100 span, but a discrete object. One bridge must stay one bridge.
        SectionLineage.Placed bridge = SectionLineage.assignSpan(p, 900, 1100);
        assertEquals("P/1", bridge.label(), "assigned by its start chainage");
        near(900, bridge.start(), "and kept intact");
        near(1100, bridge.end(), "even though it now overruns its section's end");
    }

    @Test
    void aRowExactlyOnTheCutGoesToTheSectionThatStartsThere() {
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        // Same tie-break the Chainage Locator applies at a section join: the section that
        // ENDS there loses to the one that STARTS there — that is one place, not two.
        SectionLineage.Placed point = SectionLineage.assignSpan(p, 1000, 1000);
        assertEquals("P/2", point.label());
        near(0, point.start(), "at the very start of the second section");
    }

    /* ===================== the four chainage columns ===================== */

    @Test
    void sectionLocalChainageIsAlwaysZeroToTheSectionsOwnLength() {
        // The shape every row of the real network has: Start_Chai 0, End_Chaina = Measrd_Len
        // = Rd_End_cha - Rd_Str_cha. B sits at 1000..1800 of its road but is 0..800 of itself.
        near(0, B.localStartCh(), "Start_Chai is 0 on every section");
        near(800, B.localEndCh(), "End_Chaina is the section's own length");
        near(1000, B.startCh(), "Rd_Str_cha stays absolute");
        near(1800, B.endCh(), "Rd_End_cha stays absolute");
    }

    @Test
    void splitChildrenEachRestartTheirLocalChainageAtZero() {
        // KPWD/MDR/501010104/8/1 and /8/2 in the real data are exactly this shape:
        // 0..7400 then 7400..14880 of the road, each 0..len of itself.
        SectionLineage.Section parent = new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);
        SectionLineage.SplitPlan p = SectionLineage.planSplit(parent, 1000.0, "P/1", "P/2");

        near(0, p.first().localStartCh(), "first child starts its own chainage at 0");
        near(1000, p.first().localEndCh(), "and ends at its own length");
        near(0, p.second().localStartCh(), "the second child restarts at 0 — it does not continue 1000");
        near(800, p.second().localEndCh(), "and ends at its own length, not the parent's");
    }

    @Test
    void mergedSectionsLocalChainageSpansBothLengths() {
        SectionLineage.Section r =
                SectionLineage.planMerge(List.of(A, B), "M").result();
        near(0, r.localStartCh(), "still 0");
        near(1800, r.localEndCh(), "and now runs the full merged length");
    }

    @Test
    void aSectionWhoseChainageColumnsDisagreeIsNamed() {
        // KPWD/MDR/501030101/14/2 in the current network: Measrd_Len 500, but the road
        // chainage spans 1270. Splitting it would bake the discrepancy into both children.
        SectionLineage.Section bad = new SectionLineage.Section("X", 3980.0, 5250.0, 500.0, 1270.0);
        String problem = bad.chainageInconsistency(0.5);
        assertNotNull(problem, "a 770 m disagreement must be reported");
        assertTrue(problem.contains("500") && problem.contains("1270"), "both values named: " + problem);

        assertNull(A.chainageInconsistency(0.5), "a consistent section reports nothing");
        // Field chainage is not exact to the millimetre — a small disagreement is tolerated.
        SectionLineage.Section near = new SectionLineage.Section("Y", 0.0, 1000.0, 1000.3, 1000.0);
        assertNull(near.chainageInconsistency(0.5), "30 cm is measurement noise, not an error");
    }

    /* ===================== reviewing and editing the generated chainages ===================== */

    private static final SectionLineage.Section PARENT =
            new SectionLineage.Section("P", 0.0, 1800.0, 1800.0, 1800.0);

    @Test
    void theGeneratedSplitChainagesPassTheirOwnCheck() {
        SectionLineage.SplitPlan p = SectionLineage.planSplit(PARENT, 1000.0, "P/1", "P/2");
        assertTrue(SectionLineage.verifySplitEdits(PARENT, p.first(), p.second(), 0.5).isEmpty(),
                "what planSplit generates must always be accepted by the check");
    }

    @Test
    void movingTheSplitPointIsJustARePlan() {
        // The operator edits the first half's end chainage from 1000 to 1100. That is the same
        // thing as splitting at 1100 — and re-planning cannot produce a contradiction.
        SectionLineage.SplitPlan moved = SectionLineage.planSplit(PARENT, 1100.0, "P/1", "P/2");
        near(1100, moved.first().endCh(), "first half now ends at the new point");
        near(1100, moved.second().startCh(), "and the second half follows it automatically");
        near(700, moved.second().localEndCh(), "the second half's own length shrinks to match");
        assertTrue(SectionLineage.verifySplitEdits(PARENT, moved.first(), moved.second(), 0.5).isEmpty());
    }

    @Test
    void editedHalvesThatDoNotMeetAreRejected() {
        SectionLineage.Section a = new SectionLineage.Section("P/1", 0.0, 1000.0, 1000.0, 1000.0);
        SectionLineage.Section b = new SectionLineage.Section("P/2", 1050.0, 1800.0, 750.0, 750.0);
        List<String> problems = SectionLineage.verifySplitEdits(PARENT, a, b, 0.5);
        assertTrue(problems.stream().anyMatch(s -> s.contains("do not meet")),
                "a 50 m hole inside the section must be named: " + problems);
    }

    @Test
    void editedHalvesThatDoNotSumToTheParentAreRejected() {
        // Both halves declared 1000 m: they meet, they span the parent's ends, but they claim
        // 2000 m of road where the centreline being cut is 1800.
        SectionLineage.Section a = new SectionLineage.Section("P/1", 0.0, 1000.0, 1000.0, 1000.0);
        SectionLineage.Section b = new SectionLineage.Section("P/2", 1000.0, 1800.0, 1000.0, 800.0);
        List<String> problems = SectionLineage.verifySplitEdits(PARENT, a, b, 0.5);
        assertFalse(problems.isEmpty(), "an impossible total must not pass review");
    }

    @Test
    void aSplitMayNotMoveTheSectionAlongItsRoad() {
        SectionLineage.Section a = new SectionLineage.Section("P/1", 200.0, 1200.0, 1000.0, 1000.0);
        SectionLineage.Section b = new SectionLineage.Section("P/2", 1200.0, 2000.0, 800.0, 800.0);
        List<String> problems = SectionLineage.verifySplitEdits(PARENT, a, b, 0.5);
        assertTrue(problems.stream().anyMatch(s -> s.contains("along its road")),
                "re-anchoring the section is a correction, not a split: " + problems);
    }

    @Test
    void theGeneratedMergeChainagesPassTheirOwnCheck() {
        SectionLineage.MergePlan p = SectionLineage.planMerge(List.of(A, B), "M");
        assertTrue(SectionLineage.verifyMergeEdits(p, p.result(), 0.5).isEmpty());
    }

    @Test
    void aMergedSectionMayNotBeResized() {
        SectionLineage.MergePlan p = SectionLineage.planMerge(List.of(A, B), "M");
        // Operator trims 100 m off the end. Every row on B was offset by 1000 against a total
        // of 1800 — shortening it moves all of them.
        SectionLineage.Section edited = new SectionLineage.Section("M", 0.0, 1700.0, 1700.0, 1700.0);
        List<String> problems = SectionLineage.verifyMergeEdits(p, edited, 0.5);
        assertTrue(problems.stream().anyMatch(s -> s.contains("re-based")),
                "the offsets depend on the total, and the message must say so: " + problems);
    }

    @Test
    void movingARowOfASectionNotInTheMergeIsRefused() {
        SectionLineage.MergePlan m = SectionLineage.planMerge(List.of(A, B), "M");
        assertTrue(refused(() -> SectionLineage.merged(m, "KPWD/MDR/9999/9", 0, 100)).contains("not one of"));
    }
}
