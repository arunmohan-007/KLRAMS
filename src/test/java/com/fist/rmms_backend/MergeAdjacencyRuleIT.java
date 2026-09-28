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
 * The adjacency rule for a merge, as an executable spec. Every test here uses {@code previewMerge},
 * which writes nothing.
 *
 * <p>The rule: two sections may be merged only when one's <b>Road End Chainage</b> is the other's
 * <b>Road Start Chainage</b> — they have to meet. A merge re-bases the second section's rows by
 * the first section's length, so merging across a gap would silently stretch every surveyed
 * chainage over ground that was never surveyed, and merging an overlap would stack two sets of
 * rows on the same metres. Neither raises an error later; both just read as plausible data in the
 * wrong place, which is why they are refused up front.
 *
 * <p>The figures must match EXACTLY — {@link SectionLineage#JOIN_TOLERANCE} is a millimetre, and
 * only to stay immune to floating-point representation. These two columns are operator input
 * rather than independent field measurements, so the same join is the same typed figure on both
 * sections; a discrepancy is a data error to correct, not noise to absorb. Checked against the
 * live network before this was tightened from 1 m: all 90 same-road adjacent pairs meet exactly,
 * so no legitimate merge is affected — see {@link #reportHowExactlyAdjacentPairsMeet()}.
 *
 * <p>The refusal cases need a pair whose centrelines DO join, otherwise the geometry check refuses
 * first and the adjacency rule is never reached. Such pairs are rare here, so those tests may
 * skip; the arithmetic itself is covered unconditionally by {@code SectionLineageTest}, which
 * calls {@code planMerge} directly with no database.
 */
@SpringBootTest
class MergeAdjacencyRuleIT {

    @Autowired SectionLineageService lineage;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;

    private String lbl() {
        return roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);
    }

    private String rs(String a) { return roadColumns.col(a, LayerAttributeCatalog.ROAD_START_CHAINAGE); }
    private String re(String a) { return roadColumns.col(a, LayerAttributeCatalog.ROAD_END_CHAINAGE); }
    private String nm(String a) { return roadColumns.col(a, LayerAttributeCatalog.ROAD_NAME); }

    /** Road End Chainage of one meets Road Start Chainage of the other — the merge is allowed. */
    @Test
    void sectionsThatMeetAtAChainageAreAccepted() {
        List<String> pair = pairWhere("abs(" + rs("b") + "::double precision - "
                                    + re("a") + "::double precision) <= 1.0", true);
        assumeTrue(pair != null, "no adjacent same-road pair with joining centrelines");

        Map<String, Object> out = lineage.previewMerge(pair, pair.get(0), "it");
        assertEquals("ok", out.get("status"),
                "an adjacent pair must be mergeable, but was refused: " + out.get("message"));
        System.out.println("[accepted] " + pair.get(0) + " + " + pair.get(1));
    }

    /** A gap between them: Road End Chainage of one is well short of the other's start. */
    @Test
    void sectionsWithAGapBetweenThemAreRefused() {
        /* The centrelines must join, or the geometry check refuses first and this proves
           nothing about adjacency — which is exactly what an earlier version of this test did. */
        List<String> pair = pairWhere(rs("b") + "::double precision - "
                                    + re("a") + "::double precision BETWEEN 50 AND 5000", true);
        assumeTrue(pair != null, "no same-road pair with joining lines but a chainage gap");

        Map<String, Object> out = lineage.previewMerge(pair, pair.get(0), "it");
        assertNotEquals("ok", out.get("status"), "a pair with a chainage gap must be refused");
        String msg = String.valueOf(out.get("message"));
        assertTrue(msg.contains("are not adjacent"),
                "the refusal must be the CHAINAGE adjacency one, not a geometry gap: " + msg);
        System.out.println("[refused ] " + pair.get(0) + " + " + pair.get(1) + " -> " + msg);
    }

    /** Overlapping chainage — consecutive stretches cannot share metres. */
    @Test
    void sectionsThatOverlapInChainageAreRefused() {
        List<String> pair = pairWhere(re("a") + "::double precision - "
                                    + rs("b") + "::double precision BETWEEN 50 AND 5000 "
                                  + "AND " + rs("b") + "::double precision > " + rs("a") + "::double precision",
                                    true);
        assumeTrue(pair != null, "no overlapping same-road pair with joining lines");

        Map<String, Object> out = lineage.previewMerge(pair, pair.get(0), "it");
        assertNotEquals("ok", out.get("status"), "an overlapping pair must be refused");
        assertTrue(String.valueOf(out.get("message")).contains("overlap"),
                "the refusal must name the overlap: " + out.get("message"));
        System.out.println("[refused ] " + pair.get(0) + " + " + pair.get(1)
                + " -> " + out.get("message"));
    }

    /**
     * Abutting chainage is not enough on its own. Most sections in this network start at chainage
     * 0, so two sections of unrelated roads abut by coincidence constantly — the road has to match
     * as well, read from the Road Name attribute.
     */
    @Test
    void sectionsOnDifferentRoadsAreRefusedEvenWhenTheirChainagesMeet() {
        List<Map<String, Object>> f = jdbc.queryForList(
                "SELECT a." + lbl() + " AS first, b." + lbl() + " AS second, "
              + "       " + nm("a") + " AS road_a, " + nm("b") + " AS road_b "
              + "FROM roads a JOIN roads b "
              + "  ON abs(" + rs("b") + "::double precision - " + re("a") + "::double precision) <= 1.0 "
              + " AND TRIM(" + nm("a") + ") <> TRIM(" + nm("b") + ") "
              + "WHERE a." + lbl() + " <> b." + lbl() + " "
              + "  AND " + re("a") + "::double precision > " + rs("a") + "::double precision "
              + "  AND " + re("b") + "::double precision > " + rs("b") + "::double precision "
              + "ORDER BY 1 LIMIT 1");
        assumeTrue(!f.isEmpty(), "no chainage-abutting pair on two different roads");

        List<String> pair = List.of(String.valueOf(f.get(0).get("first")),
                                    String.valueOf(f.get(0).get("second")));
        Map<String, Object> out = lineage.previewMerge(pair, pair.get(0), "it");
        assertNotEquals("ok", out.get("status"),
                "sections of two different roads must be refused however well their chainages meet");
        assertTrue(String.valueOf(out.get("message")).contains("same road"),
                "the refusal should name the road mismatch, but said: " + out.get("message"));
        System.out.println("[refused ] \"" + f.get(0).get("road_a") + "\" + \""
                + f.get(0).get("road_b") + "\" -> " + out.get("message"));
    }

    /**
     * Adjacency is judged on <b>Road Start Chainage</b> / <b>Road End Chainage</b> — the ABSOLUTE
     * pair — and not on Section Start/End Chainage.
     *
     * <p>{@code roads} carries two chainage pairs and only one of them can answer "are these two
     * sections next to each other". Section Start Chainage is 0 on essentially every row and
     * Section End Chainage is just the section's own span, so judging adjacency on that pair
     * would make every section look like 0..len — each pair reading as a total overlap, and every
     * merge in the network refused for a reason that is not true.
     *
     * <p>Worth pinning rather than reading off the source: the columns reach the section through
     * a seven-field positional record, where a transposed pair still compiles and still returns
     * plausible numbers.
     */
    @Test
    void adjacencyIsJudgedOnTheRoadChainageAttributesNotTheSectionOnes() {
        SectionLineageService.RoadColumnMap c = lineage.roadColumns();
        assertTrue(c.missing().isEmpty(), "road network is missing: " + c.missing());

        System.out.println("[attrs] " + LayerAttributeCatalog.ROAD_START_CHAINAGE + " -> " + c.roadStart());
        System.out.println("[attrs] " + LayerAttributeCatalog.ROAD_END_CHAINAGE + "   -> " + c.roadEnd());
        System.out.println("[attrs] " + LayerAttributeCatalog.SECTION_START_CHAINAGE + " -> " + c.sectionStart());
        System.out.println("[attrs] " + LayerAttributeCatalog.SECTION_END_CHAINAGE + "   -> " + c.sectionEnd());

        assertNotEquals(c.roadStart(), c.sectionStart(),
                "Road Start Chainage and Section Start Chainage resolved to the SAME column");
        assertNotEquals(c.roadEnd(), c.sectionEnd(),
                "Road End Chainage and Section End Chainage resolved to the SAME column");

        /* Pick a section whose two pairs genuinely differ, so the assertion can tell them apart:
           one that does not start at road chainage 0. */
        Map<String, Object> row = jdbc.queryForList(
                "SELECT r.\"" + c.label() + "\" AS label, "
              + "       r.\"" + c.roadStart() + "\"::double precision AS road_start, "
              + "       r.\"" + c.roadEnd() + "\"::double precision AS road_end, "
              + "       r.\"" + c.sectionStart() + "\"::double precision AS sec_start, "
              + "       r.\"" + c.sectionEnd() + "\"::double precision AS sec_end "
              + "FROM roads r WHERE r.\"" + c.roadStart() + "\"::double precision > 0 "
              + "  AND r.\"" + c.roadEnd() + "\"::double precision > r.\"" + c.roadStart() + "\"::double precision "
              + "ORDER BY 1 LIMIT 1").stream().findFirst().orElse(null);
        assumeTrue(row != null, "every section starts at road chainage 0 in this database");

        String label = String.valueOf(row.get("label"));
        SectionLineage.Section s = lineage.loadSection(label);
        assertNotNull(s, label + " did not load");

        System.out.printf("[attrs] %s: road %s..%s, section %s..%s; loadSection gave %s..%s%n",
                label, row.get("road_start"), row.get("road_end"),
                row.get("sec_start"), row.get("sec_end"), s.startCh(), s.endCh());

        assertEquals(((Number) row.get("road_start")).doubleValue(), s.startCh(), 1e-9,
                "the section's start came from the wrong attribute");
        assertEquals(((Number) row.get("road_end")).doubleValue(), s.endCh(), 1e-9,
                "the section's end came from the wrong attribute");
        /* And it is demonstrably NOT the section-local pair. */
        assertNotEquals(((Number) row.get("sec_start")).doubleValue(), s.startCh(),
                "the section's start came from Section Start Chainage");
    }

    /**
     * Read-only: how exactly do the network's adjacent pairs actually meet?
     *
     * <p>Decides whether {@link SectionLineage#JOIN_TOLERANCE} can be zero. If every pair meets
     * exactly, the tolerance costs nothing and exact equality is the honest rule; if real pairs
     * are out by tenths of a metre, demanding exact equality would refuse legitimate merges over
     * field-measurement noise.
     */
    @Test
    void reportHowExactlyAdjacentPairsMeet() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a." + lbl() + " AS first, b." + lbl() + " AS second, "
              + "       " + re("a") + "::double precision AS a_end, "
              + "       " + rs("b") + "::double precision AS b_start, "
              + "       abs(" + rs("b") + "::double precision - " + re("a") + "::double precision) AS delta, "
              + "       ST_GeometryType(ST_LineMerge(ST_Union(a.geom, b.geom))) = 'ST_LineString' AS lines_join "
              + "FROM roads a JOIN roads b "
              + "  ON TRIM(" + nm("a") + ") = TRIM(" + nm("b") + ") AND a." + lbl() + " <> b." + lbl() + " "
              + " AND abs(" + rs("b") + "::double precision - " + re("a") + "::double precision) <= 1.0 "
              + "WHERE a.geom IS NOT NULL AND b.geom IS NOT NULL "
              + "  AND " + re("a") + "::double precision > " + rs("a") + "::double precision "
              + "  AND " + re("b") + "::double precision > " + rs("b") + "::double precision "
              + "ORDER BY delta DESC, 1");

        long exact = rows.stream().filter(r -> ((Number) r.get("delta")).doubleValue() == 0).count();
        long joinable = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("lines_join"))).count();
        long inexactJoinable = rows.stream().filter(r ->
                Boolean.TRUE.equals(r.get("lines_join"))
             && ((Number) r.get("delta")).doubleValue() != 0).count();

        System.out.printf("%n[tolerance] %d same-road pairs meet within 1 m; %d meet EXACTLY%n",
                rows.size(), exact);
        System.out.printf("[tolerance] of those, %d have joining centrelines (actually mergeable), "
                        + "%d of which do NOT meet exactly%n", joinable, inexactJoinable);
        rows.stream().filter(r -> ((Number) r.get("delta")).doubleValue() != 0).limit(12)
            .forEach(r -> System.out.printf("[tolerance]   %s ends %s, %s starts %s  (out by %s m)%s%n",
                    r.get("first"), r.get("a_end"), r.get("second"), r.get("b_start"), r.get("delta"),
                    Boolean.TRUE.equals(r.get("lines_join")) ? "  <- lines join, would be BLOCKED" : ""));
        System.out.println();
    }

    /**
     * A same-road pair matching {@code chainagePredicate}.
     *
     * @param requireJoinedLines when true, also require the centrelines to merge into one line.
     *        Needed by every case here: the success case cannot succeed without it, and in the
     *        refusal cases a refusal for bad geometry would otherwise masquerade as a refusal
     *        for adjacency, since geometry is checked first.
     */
    private List<String> pairWhere(String chainagePredicate, boolean requireJoinedLines) {
        List<Map<String, Object>> f = jdbc.queryForList(
                "SELECT a." + lbl() + " AS first, b." + lbl() + " AS second FROM roads a JOIN roads b "
              + "  ON TRIM(" + nm("a") + ") = TRIM(" + nm("b") + ") AND a." + lbl() + " <> b." + lbl() + " "
              + " AND " + chainagePredicate + " "
              + "WHERE a.geom IS NOT NULL AND b.geom IS NOT NULL "
              + "  AND ST_GeometryType(ST_LineMerge(a.geom)) = 'ST_LineString' "
              + "  AND ST_GeometryType(ST_LineMerge(b.geom)) = 'ST_LineString' "
              + "  AND " + re("a") + "::double precision > " + rs("a") + "::double precision "
              + "  AND " + re("b") + "::double precision > " + rs("b") + "::double precision "
              + (requireJoinedLines
                    ? "  AND ST_GeometryType(ST_LineMerge(ST_Union(a.geom, b.geom))) = 'ST_LineString' "
                    : "")
              + "ORDER BY 1 LIMIT 1");
        if (f.isEmpty()) return null;
        return List.of(String.valueOf(f.get(0).get("first")), String.valueOf(f.get(0).get("second")));
    }
}
