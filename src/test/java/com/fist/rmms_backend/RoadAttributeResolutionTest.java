package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolving a road-network field by what it MEANS rather than by the column name one
 * shapefile import happened to produce.
 *
 * <p>The road network's columns come from a DBF, which truncates every field name to 10
 * characters, so {@code Rd_Str_cha} is a fact about one survey return rather than about the
 * system. The system attribute name — "Road Start Chainage" — is the stable identifier, and a
 * module that hard-codes the column instead is pinned to that one return.
 *
 * <p>These tests pin the contract the linear-reference modules rely on: ask for the meaning,
 * get whichever column currently carries it, and get null rather than a guess when none does.
 */
class RoadAttributeResolutionTest {

    /** The columns the current Thiruvananthapuram network actually has. */
    private static final List<String> CURRENT = List.of(
            "Section_La", "Road_Name", "Road_Num", "Road_Class", "Dig_L", "End_Chaina",
            "Rd_Str_Loc", "Owner", "Single_Du", "Measrd_Len", "Road_Type", "Sectn_Code",
            "Rd_Str_cha", "Rd_End_cha", "Environmen", "Start_Chai", "District");

    private static String resolve(String systemName, List<String> columns) {
        return LayerAttributeCatalog.roadColumnFor(systemName, columns);
    }

    @Test
    void theSixStructuralAttributesResolveAgainstTheCurrentNetwork() {
        assertEquals("Section_La", resolve(LayerAttributeCatalog.SECTION_LABEL, CURRENT));
        assertEquals("Rd_Str_cha", resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, CURRENT));
        assertEquals("Rd_End_cha", resolve(LayerAttributeCatalog.ROAD_END_CHAINAGE, CURRENT));
        assertEquals("Start_Chai", resolve(LayerAttributeCatalog.SECTION_START_CHAINAGE, CURRENT));
        assertEquals("End_Chaina", resolve(LayerAttributeCatalog.SECTION_END_CHAINAGE, CURRENT));
        assertEquals("Measrd_Len", resolve(LayerAttributeCatalog.MEASURED_LENGTH, CURRENT));
    }

    @Test
    void theTwoChainagePairsAreNeverConfusedForEachOther() {
        // The whole module depends on these being distinct: the Road pair is absolute road
        // chainage and is the placement divisor; the other is section-local and starts at 0.
        // Resolving one to the other's column would shift every asset on the network.
        assertNotEquals(resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, CURRENT),
                        resolve(LayerAttributeCatalog.SECTION_START_CHAINAGE, CURRENT));
        assertNotEquals(resolve(LayerAttributeCatalog.ROAD_END_CHAINAGE, CURRENT),
                        resolve(LayerAttributeCatalog.SECTION_END_CHAINAGE, CURRENT));
    }

    @Test
    void aReImportThatSpellsTheFieldDifferentlyStillResolves() {
        // The case this indirection exists for: the next survey return ships longer field
        // names, DBF truncates them elsewhere, and every module that hard-coded the old
        // spelling would break silently. Nothing here needed to change.
        List<String> renamed = List.of(
                "Section_La", "Road_Name", "Rd_Start_c", "Rd_End_c", "Str_Chain", "End_Chain",
                "Measurd_Le", "District");
        assertEquals("Rd_Start_c", resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, renamed));
        assertEquals("Rd_End_c", resolve(LayerAttributeCatalog.ROAD_END_CHAINAGE, renamed));
        assertEquals("Str_Chain", resolve(LayerAttributeCatalog.SECTION_START_CHAINAGE, renamed));
        assertEquals("End_Chain", resolve(LayerAttributeCatalog.SECTION_END_CHAINAGE, renamed));
        assertEquals("Measurd_Le", resolve(LayerAttributeCatalog.MEASURED_LENGTH, renamed));
    }

    @Test
    void aColumnThatIsNotPresentIsNeverReturned() {
        // "Measrd_Ln" is a declared alias but does not exist in this schema. A query naming a
        // column that is not there fails outright, and inside a transaction that rolls back
        // everything else in it (CLAUDE.md records this as a real bug). Resolution returning
        // only columns that are actually present is what makes listing the alias safe.
        List<String> withoutMeasured = List.of("Section_La", "Rd_Str_cha", "Rd_End_cha");
        assertNull(resolve(LayerAttributeCatalog.MEASURED_LENGTH, withoutMeasured),
                "an absent attribute must resolve to null, not to a plausible-looking name");
    }

    @Test
    void theCanonicalSpellingWinsOverAnAlias() {
        List<String> both = List.of("Rd_Str_cha", "Rd_Start_c");
        assertEquals("Rd_Str_cha", resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, both),
                "the declared column is the answer, not whichever alias was listed first");
    }

    @Test
    void resolutionIsCaseInsensitiveOnTheColumnSide() {
        // Postgres folds unquoted identifiers to lower case, so the same network can report
        // its columns either way depending on how it was created.
        assertEquals("rd_str_cha", resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE,
                List.of("section_la", "rd_str_cha", "rd_end_cha")));
    }

    @Test
    void everyStructuralAttributeMapsBackToItsSystemName() {
        assertEquals(LayerAttributeCatalog.ROAD_START_CHAINAGE,
                LayerAttributeCatalog.roadSystemName("Rd_Str_cha"));
        assertEquals(LayerAttributeCatalog.SECTION_START_CHAINAGE,
                LayerAttributeCatalog.roadSystemName("Start_Chai"));
        assertEquals(LayerAttributeCatalog.MEASURED_LENGTH,
                LayerAttributeCatalog.roadSystemName("Measrd_Len"));
        assertNull(LayerAttributeCatalog.roadSystemName("Not_A_Field"));
    }

    @Test
    void nullsAndEmptyColumnListsAreAnswerNotACrash() {
        assertNull(resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, List.of()));
        assertNull(resolve(null, CURRENT));
        assertNull(resolve(LayerAttributeCatalog.ROAD_START_CHAINAGE, null));
    }
}
