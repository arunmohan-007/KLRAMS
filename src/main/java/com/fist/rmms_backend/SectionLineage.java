package com.fist.rmms_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KLRAMS — the arithmetic of splitting one road section into two, and of merging several
 * into one. Pure maths: no JDBC, no PostGIS, no Spring. Everything that touches the
 * database is built on top of this in {@code SectionLineageService}.
 *
 * <h2>Why this is a class of its own</h2>
 *
 * Splitting and merging sections looks like a labelling job and is not one.
 * {@link SectionRenameService} moves a string; this moves the coordinate system that every
 * survey record on the section is measured in.
 *
 * Every dependent table stores chainage <b>section-local</b>: 0 at the section's own start.
 * {@code roads} stores <b>absolute road chainage</b>, in {@code Rd_Str_cha}..{@code Rd_End_cha}.
 * The whole system converts between the two with one formula, in
 * {@link PlacementService#LEN_EXPR} and again in every tile and segment builder:
 *
 * <pre>fraction along the centreline = local chainage / (Rd_End_cha − Rd_Str_cha)</pre>
 *
 * So merging a 1000 m section with the 800 m section after it does not just relabel the
 * second one's rows — <b>every one of them must have 1000 added to its chainage</b>. Relabel
 * without re-basing and the second section's entire survey history stacks onto the first
 * 800 metres of the merged road. Nothing raises. The map draws. Every asset, every condition
 * segment, every traffic station is in the wrong place, and the error is proportional, so it
 * looks plausible everywhere.
 *
 * That re-basing is what this class computes, and it is the reason a new section's start
 * chainage, end chainage and measured length are mandatory rather than nice to have: they
 * are the divisor, and an inconsistent set of three shifts everything placed against them.
 *
 * <h2>What it deliberately does not decide</h2>
 *
 * <b>Geometry.</b> Cutting the centreline is {@code ST_LineSubstring} at
 * {@link SplitPlan#fraction()} — computed here, applied in SQL, so the child centreline is
 * cut with the identical calibration the data is re-based with. Any second formula anywhere
 * would put the geometry and the data that rides on it in different places.
 *
 * <b>What to do with a row that straddles the split point.</b> That is a policy question and
 * the answer differs by layer: an interval measurement (a condition row's IRI over 900..1100)
 * is genuinely divisible, a discrete object (a bridge over 900..1100) is not. Both behaviours
 * are offered — {@link #divideSpan} and {@link #assignSpan} — and the caller picks per table.
 */
final class SectionLineage {

    /** Chainage comparisons are in metres; a millimetre is noise, not a gap. */
    static final double EPS = 1e-3;

    /**
     * How far apart two sections' chainages may be and still count as contiguous, in metres.
     *
     * <p>Effectively zero — one section's Road End Chainage must BE the next one's Road Start
     * Chainage. These two columns are not independent field measurements that could drift apart;
     * they are entered by the operator, so the same join is the same typed figure on both
     * sections and anything else is an error in the data rather than survey noise. Any slack here
     * would be absorbed into the merged length and silently stretch every re-based chainage.
     *
     * <p>It is {@link #EPS} rather than a literal 0 only to stay immune to floating-point
     * representation: a millimetre cannot be a real gap, and comparing doubles for exact equality
     * would refuse a pair that agrees to every digit anyone typed.
     *
     * <p>Confirmed against the live network before tightening from the original 1.0: all 90
     * same-road adjacent pairs meet exactly, so none of the 37 actually-mergeable pairs is
     * affected by this.
     */
    static final double JOIN_TOLERANCE = EPS;

    private SectionLineage() {}

    /* ===================== the section ===================== */

    /**
     * One road section's linear-reference calibration, as {@code roads} holds it.
     *
     * @param label       {@code Section_La}
     * @param startCh     {@code Rd_Str_cha} — absolute road chainage at the section's start
     * @param endCh       {@code Rd_End_cha} — absolute road chainage at its end
     * @param measuredLen {@code Measrd_Len}, or null
     * @param geomLen     geodesic length of the drawn centreline, or null
     */
    record Section(String label, Double startCh, Double endCh, Double measuredLen, Double geomLen) {

        /**
         * The reference length every placement query divides by, in the priority the whole
         * codebase agrees on: surveyed chainage span, then measured length, then the drawn
         * line. Must stay identical to {@link PlacementService#LEN_EXPR} — see CLAUDE.md.
         */
        double refLen() {
            if (startCh != null && endCh != null) {
                double span = endCh - startCh;
                if (Math.abs(span) > EPS) return span;
            }
            if (measuredLen != null && Math.abs(measuredLen) > EPS) return measuredLen;
            if (geomLen != null) return geomLen;
            return 0;
        }

        /**
         * {@code Start_Chai} — the section's own start chainage, which is always 0.
         *
         * <p>{@code roads} carries FOUR chainage columns, two pairs with different meanings,
         * and the pair a query picks decides whether it is right:
         *
         * <ul>
         *   <li>{@code Rd_Str_cha}/{@code Rd_End_cha} — ABSOLUTE road chainage. Where this
         *       section sits along the whole road. This is the pair every placement query
         *       divides by, and the pair that changes for every child of a split.</li>
         *   <li>{@code Start_Chai}/{@code End_Chaina} — SECTION-LOCAL chainage: 0 to the
         *       section's own length. This is the frame every dependent table's chainage is
         *       already expressed in.</li>
         * </ul>
         *
         * <p>Verified against the 550-section Thiruvananthapuram network: {@code Start_Chai}
         * is 0 on every row without exception, and {@code End_Chaina} equals both
         * {@code Measrd_Len} and {@code Rd_End_cha − Rd_Str_cha} on all but three.
         *
         * <p>Derived rather than stored on this record on purpose. They are not independent
         * facts a caller could get wrong — they are restatements of the length, and letting a
         * split or merge write a {@code Start_Chai} that is not 0, or an {@code End_Chaina}
         * that disagrees with the span, would create exactly the inconsistency
         * {@link #chainageInconsistency} exists to detect.
         */
        double localStartCh() {
            return 0;
        }

        /** {@code End_Chaina} — the section's own end chainage, which is its length. */
        double localEndCh() {
            return Math.abs(refLen());
        }

        /**
         * Names the disagreement between this section's four chainage columns, or null when
         * they agree. {@code Measrd_Len} and {@code End_Chaina} should both equal the absolute
         * span, and where they do not, one of them is a data-entry error.
         *
         * <p>Checked BEFORE a split or merge, not after. A section whose columns already
         * disagree cannot be split safely: whichever value the tool picked would be baked into
         * both children, the other would be silently discarded, and the discrepancy would stop
         * being diagnosable because there would no longer be a single section to compare
         * against. Three sections in the current network are in this state.
         *
         * @param tolerance metres of disagreement to allow — field-measured chainage is not
         *                  exact to the millimetre.
         */
        String chainageInconsistency(double tolerance) {
            if (!hasChainageRange()) return null;   // a different refusal, raised elsewhere
            double span = Math.abs(endCh - startCh);
            if (measuredLen != null && Math.abs(Math.abs(measuredLen) - span) > tolerance)
                return "Section \"" + label + "\" has Measrd_Len " + fmt(measuredLen)
                     + " m but its road chainage spans " + fmt(span) + " m ("
                     + fmt(startCh) + ".." + fmt(endCh) + ").";
            return null;
        }

        /**
         * Whether this section carries a usable absolute chainage range.
         *
         * <p>Split and merge both refuse a section without one, and the refusal is not
         * fussiness. A section falling back to {@code Measrd_Len} has a length but no position
         * on the road, so "split at road chainage 2 600" has no meaning against it, and two
         * such sections cannot be shown to be adjacent. Worse, its {@code refLen} and its
         * {@code endCh − startCh} are then different numbers, and the offsets computed from
         * one would be applied to data placed with the other.
         */
        boolean hasChainageRange() {
            return startCh != null && endCh != null && Math.abs(endCh - startCh) > EPS;
        }

        /** True when the section is digitised against the direction of increasing chainage. */
        boolean isReversed() {
            return startCh != null && endCh != null && endCh < startCh;
        }
    }

    /* ===================== split ===================== */

    /**
     * A split, resolved: the two child sections and where the cut falls.
     *
     * @param parent      the section being split
     * @param first       the child covering the parent's start up to the cut
     * @param second      the child covering the cut to the parent's end
     * @param localSplit  the cut as a section-local chainage on the parent — the amount that
     *                    must be SUBTRACTED from every row moving to {@code second}
     * @param fraction    the cut as a fraction of the parent's length, for
     *                    {@code ST_LineSubstring}
     */
    record SplitPlan(Section parent, Section first, Section second, double localSplit, double fraction) {}

    /**
     * Plans a split of {@code parent} at an absolute road chainage.
     *
     * <p>The cut is given in road chainage — the number an engineer quotes — not as an offset,
     * for the same reason {@code RoadController.locateChainage} takes one: it is the only form
     * that is unambiguous when several sections of a road are in front of you.
     *
     * <p>Both children inherit the parent's calibration exactly: their chainage ranges abut at
     * the cut and sum to the parent's, and the measured length is divided in the parent's own
     * proportion rather than recomputed, so a section whose {@code Measrd_Len} already differed
     * from its chainage span keeps that difference distributed across both halves instead of
     * having it silently corrected on one side only.
     */
    static SplitPlan planSplit(Section parent, double splitAbsCh, String firstLabel, String secondLabel) {
        require(parent != null, "No section to split.");
        requireLabel(firstLabel, "first");
        requireLabel(secondLabel, "second");
        require(!firstLabel.equals(secondLabel),
                "Both halves were given the same label \"" + firstLabel + "\".");
        require(parent.hasChainageRange(),
                "Section \"" + parent.label() + "\" has no usable start/end chainage, so there is no "
              + "road chainage to split it at. Give it Rd_Str_cha and Rd_End_cha first.");
        require(!parent.isReversed(),
                "Section \"" + parent.label() + "\" runs from chainage " + parent.startCh() + " down to "
              + parent.endCh() + ". Splitting a reversed section is not supported — correct its "
              + "chainage direction on the road network first.");

        double len = parent.refLen();
        double local = splitAbsCh - parent.startCh();
        require(local > EPS && local < len - EPS,
                "Chainage " + splitAbsCh + " is not inside section \"" + parent.label() + "\" ("
              + parent.startCh() + ".." + parent.endCh() + "). A split point must fall strictly "
              + "within the section — splitting at either end would produce an empty half.");

        double ratio = local / len;
        Double ml = parent.measuredLen();
        Double ml1 = ml == null ? null : ml * ratio;
        Double ml2 = ml == null ? null : ml * (1 - ratio);
        Double gl = parent.geomLen();

        Section a = new Section(firstLabel, parent.startCh(), splitAbsCh, ml1,
                gl == null ? null : gl * ratio);
        Section b = new Section(secondLabel, splitAbsCh, parent.endCh(), ml2,
                gl == null ? null : gl * (1 - ratio));
        return new SplitPlan(parent, a, b, local, ratio);
    }

    /* ===================== merge ===================== */

    /**
     * A merge, resolved: the single resulting section and, per contributing label, the amount
     * to ADD to every one of its rows' chainages.
     */
    record MergePlan(List<Section> sources, Section result, Map<String, Double> offsets) {}

    /**
     * Plans a merge of two or more sections into one.
     *
     * <p>The inputs are sorted into chainage order here rather than trusted in the order given:
     * the offset each section's data receives is the total length of everything before it, so
     * an input list in the wrong order produces offsets that are individually plausible and
     * collectively wrong.
     *
     * <p>Three refusals, each of which would otherwise corrupt silently:
     *
     * <ul>
     *   <li><b>A gap.</b> Sections that do not abut cannot be merged — the merged section would
     *       claim length that was never surveyed, and every row after the gap would sit that far
     *       from where it belongs.</li>
     *   <li><b>An overlap.</b> Two sections covering the same road chainage are not consecutive;
     *       they are a duplicate, or a dual carriageway, and merging them stacks two surveys.</li>
     *   <li><b>A reversed section.</b> Its chainage runs against the others', so adding a
     *       positive offset to its rows walks them backwards.</li>
     * </ul>
     */
    static MergePlan planMerge(List<Section> sections, String resultLabel) {
        require(sections != null && sections.size() >= 2, "A merge needs at least two sections.");
        requireLabel(resultLabel, "result");

        // Before anything else: the same section listed twice reads as a 100% overlap with
        // itself, and would be reported as one. Named for what it is instead.
        List<String> seen = new ArrayList<>();
        for (Section s : sections) {
            require(!seen.contains(s.label()), "Section \"" + s.label() + "\" was listed twice.");
            seen.add(s.label());
        }

        for (Section s : sections) {
            require(s.hasChainageRange(),
                    "Section \"" + s.label() + "\" has no usable start/end chainage, so it cannot be "
                  + "shown to be adjacent to the others and its data cannot be re-based.");
            require(!s.isReversed(),
                    "Section \"" + s.label() + "\" runs from chainage " + s.startCh() + " down to "
                  + s.endCh() + ", against the others. Correct its chainage direction on the road "
                  + "network before merging.");
        }

        List<Section> ordered = new ArrayList<>(sections);
        ordered.sort((x, y) -> Double.compare(x.startCh(), y.startCh()));

        for (int i = 1; i < ordered.size(); i++) {
            Section prev = ordered.get(i - 1), next = ordered.get(i);
            double gap = next.startCh() - prev.endCh();
            require(gap <= JOIN_TOLERANCE,
                    "Sections \"" + prev.label() + "\" and \"" + next.label() + "\" are not adjacent: "
                  + "\"" + prev.label() + "\" ends at chainage " + prev.endCh() + " but \""
                  + next.label() + "\" starts at " + next.startCh() + ", a gap of " + fmt(gap)
                  + " m. The two must be the same figure — correct them on the road network "
                  + "before merging, or the gap is absorbed into the merged length and every "
                  + "re-based chainage is stretched by it.");
            require(gap >= -JOIN_TOLERANCE,
                    "Sections \"" + prev.label() + "\" and \"" + next.label() + "\" overlap by "
                  + fmt(-gap) + " m of road chainage. They are not consecutive stretches — check "
                  + "whether this is a duplicate section or a dual carriageway.");
        }

        Map<String, Double> offsets = new LinkedHashMap<>();
        double acc = 0;
        for (Section s : ordered) {
            offsets.put(s.label(), acc);
            acc += s.refLen();
        }

        Section first = ordered.get(0), last = ordered.get(ordered.size() - 1);
        Double ml = 0.0;
        for (Section s : ordered) {
            if (s.measuredLen() == null) { ml = null; break; }
            ml += s.measuredLen();
        }
        Double gl = 0.0;
        for (Section s : ordered) {
            if (s.geomLen() == null) { gl = null; break; }
            gl += s.geomLen();
        }
        Section result = new Section(resultLabel, first.startCh(), last.endCh(), ml, gl);
        return new MergePlan(ordered, result, offsets);
    }

    /* ===================== checking what the user edited ===================== */

    /**
     * Checks a split whose chainages the user has edited, and names every way the edited
     * values no longer describe the parent they came from. Empty list means they are sound.
     *
     * <p>{@link #planSplit} generates the four chainage columns; the operator reviews them and
     * may correct any of them. That freedom is necessary — the generated values are only as
     * good as the parent's, and three sections in the current network have parents whose
     * columns already disagree. But an edited set can describe a road that does not exist, and
     * the failure is silent, because the child centrelines are still cut from the parent's line
     * at the generated fraction while the chainages now claim something else. Everything placed
     * on them then shifts proportionally.
     *
     * <p>Three invariants, each of which the geometry depends on:
     *
     * <ul>
     *   <li><b>The children abut.</b> The first must end exactly where the second starts,
     *       or the split has invented a gap or an overlap inside one section.</li>
     *   <li><b>They still span the parent.</b> The first starts where the parent started and
     *       the second ends where it ended — otherwise the split has quietly moved the section
     *       along its road, which is a different edit and belongs in a correction, not here.</li>
     *   <li><b>Their lengths sum to the parent's.</b> The centreline is being divided, not
     *       lengthened; two children totalling more or less than the line they were cut from
     *       is the inconsistency that shifts every row placed against them.</li>
     * </ul>
     *
     * <p>Note that MOVING the split point needs none of this: re-running {@link #planSplit}
     * with the new chainage regenerates a consistent set. Editing the first child's end
     * chainage to 1100 and re-planning at 1100 are the same operation, and re-planning is
     * the one that cannot produce a contradiction.
     */
    static List<String> verifySplitEdits(Section parent, Section first, Section second, double tolerance) {
        List<String> problems = new ArrayList<>();
        if (!first.hasChainageRange() || !second.hasChainageRange()) {
            problems.add("Both halves need a start and end road chainage.");
            return problems;
        }
        if (Math.abs(first.endCh() - second.startCh()) > tolerance)
            problems.add("The halves do not meet: \"" + first.label() + "\" ends at " + fmt(first.endCh())
                       + " but \"" + second.label() + "\" starts at " + fmt(second.startCh()) + ".");
        if (Math.abs(first.startCh() - parent.startCh()) > tolerance)
            problems.add("\"" + first.label() + "\" starts at " + fmt(first.startCh()) + ", but the section "
                       + "being split starts at " + fmt(parent.startCh()) + ". A split cannot move the "
                       + "section along its road.");
        if (Math.abs(second.endCh() - parent.endCh()) > tolerance)
            problems.add("\"" + second.label() + "\" ends at " + fmt(second.endCh()) + ", but the section "
                       + "being split ends at " + fmt(parent.endCh()) + ".");
        double sum = Math.abs(first.refLen()) + Math.abs(second.refLen());
        if (Math.abs(sum - Math.abs(parent.refLen())) > tolerance)
            problems.add("The two halves total " + fmt(sum) + " m but the section being split is "
                       + fmt(Math.abs(parent.refLen())) + " m. The centreline is being divided, not "
                       + "resized — everything placed on these sections would shift.");
        for (Section s : List.of(first, second)) {
            String bad = s.chainageInconsistency(tolerance);
            if (bad != null) problems.add(bad);
        }
        return problems;
    }

    /**
     * Checks a merge result whose chainages the user has edited, against the sections it was
     * built from. Empty list means they are sound.
     *
     * <p>The merged section's length is not a free choice: it is the divisor every re-based row
     * was offset against by {@link #planMerge}. Shorten it by 100 m and every row on the last
     * contributing section lands short of where its offset put it.
     */
    static List<String> verifyMergeEdits(MergePlan plan, Section edited, double tolerance) {
        List<String> problems = new ArrayList<>();
        if (!edited.hasChainageRange()) {
            problems.add("The merged section needs a start and end road chainage.");
            return problems;
        }
        List<Section> src = plan.sources();
        Section first = src.get(0), last = src.get(src.size() - 1);
        if (Math.abs(edited.startCh() - first.startCh()) > tolerance)
            problems.add("The merged section starts at " + fmt(edited.startCh()) + ", but \""
                       + first.label() + "\" — the first section in it — starts at "
                       + fmt(first.startCh()) + ".");
        if (Math.abs(edited.endCh() - last.endCh()) > tolerance)
            problems.add("The merged section ends at " + fmt(edited.endCh()) + ", but \""
                       + last.label() + "\" — the last section in it — ends at "
                       + fmt(last.endCh()) + ".");
        double expected = 0;
        for (Section s : src) expected += Math.abs(s.refLen());
        if (Math.abs(Math.abs(edited.refLen()) - expected) > tolerance)
            problems.add("The merged section is " + fmt(Math.abs(edited.refLen())) + " m but the sections "
                       + "in it total " + fmt(expected) + " m. That total is what every row's chainage "
                       + "was re-based against, so changing it would move all of them.");
        String bad = edited.chainageInconsistency(tolerance);
        if (bad != null) problems.add(bad);
        return problems;
    }

    /* ===================== moving the data ===================== */

    /** Where one dependent row ends up: which child/merged label, and its new local chainages. */
    record Placed(String label, double start, double end) {}

    /**
     * Re-bases one interval row onto the split's children, dividing it when it straddles the cut.
     *
     * <p>For layers whose measurement is per length — a condition row's IRI, cracking or
     * rutting over 900..1100 — both halves of a divided row carry the same values, because that
     * is what the measurement asserts about every metre of its span.
     *
     * <p>Returns one {@link Placed} for a row that falls wholly on one side, two for a row that
     * straddles. Never zero.
     */
    static List<Placed> divideSpan(SplitPlan p, double start, double end) {
        require(end >= start, "A row's end chainage (" + end + ") is before its start (" + start + ").");
        double s = p.localSplit();
        if (end <= s + EPS) return List.of(new Placed(p.first().label(), start, end));
        if (start >= s - EPS) return List.of(new Placed(p.second().label(), start - s, end - s));
        return List.of(new Placed(p.first().label(), start, s),
                       new Placed(p.second().label(), 0, end - s));
    }

    /**
     * Re-bases one indivisible row onto the split's children, whole.
     *
     * <p>For discrete objects — a bridge, a culvert, an FWD stretch. Halving a bridge at the
     * split point would produce two bridges where there is one; the object is assigned instead
     * to the child containing its START chainage, which is the end an asset is identified by
     * everywhere else in the system.
     *
     * <p>A zero-length row (a point asset: {@code start == end}) is the same case, so culverts
     * and traffic stations go through here too. A row landing exactly on the cut goes to the
     * second child — the section that STARTS there, matching the tie-break
     * {@code RoadController.locateChainage} already applies to a chainage on a section join.
     */
    static Placed assignSpan(SplitPlan p, double start, double end) {
        require(end >= start, "A row's end chainage (" + end + ") is before its start (" + start + ").");
        double s = p.localSplit();
        return start < s - EPS
                ? new Placed(p.first().label(), start, end)
                : new Placed(p.second().label(), start - s, end - s);
    }

    /** Re-bases one row of a contributing section onto the merged section. */
    static Placed merged(MergePlan p, String sourceLabel, double start, double end) {
        Double off = p.offsets().get(sourceLabel);
        require(off != null, "\"" + sourceLabel + "\" is not one of the sections being merged.");
        return new Placed(p.result().label(), start + off, end + off);
    }

    /* ===================== internals ===================== */

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static void requireLabel(String label, String which) {
        require(label != null && !label.isBlank(), "The " + which + " section label is required.");
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 1000) / 1000.0);
    }
}
