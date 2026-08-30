package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The cases {@link ImportText} exists for, written from the one that actually
 * happened: an owner value stored as "PWD Maintenanace\n" on both carriageways
 * of MC Road, which showed up in the dashboard as a second owner identical to
 * the first.
 */
class ImportTextTest {

    @Test
    void stripsTheTrailingNewlineThatSplitAnOwnerInTwo() {
        assertEquals("PWD Maintenanace", ImportText.clean("PWD Maintenanace\n"));
        assertEquals("PWD Maintenanace", ImportText.clean("PWD Maintenanace\r\n"));
        assertEquals("PWD Maintenanace", ImportText.clean("  PWD Maintenanace\t "));
    }

    @Test
    void stripsSpreadsheetArtefactsThatStringTrimLeavesBehind() {
        assertEquals("KRFB", ImportText.clean(" KRFB "));   // non-breaking space
        assertEquals("KRFB", ImportText.clean("﻿KRFB"));         // byte-order mark
    }

    @Test
    void leavesTheInsideOfAValueAlone() {
        // Internal spacing can be meaningful, so only the ends are touched.
        assertEquals("PWD  Section", ImportText.clean("  PWD  Section  "));
        assertEquals("Road\nName", ImportText.clean("Road\nName"));
    }

    @Test
    void doesNotCorrectSpelling() {
        // A real misspelling must stay visible: it is fixed in the road data,
        // not smoothed over on the way in.
        assertEquals("PWD Maintenanace", ImportText.clean("PWD Maintenanace"));
    }

    @Test
    void passesCleanValuesThroughUntouched() {
        String s = "KSTP";
        assertSame(s, ImportText.clean(s), "an already-clean value should not be copied");
        assertNull(ImportText.clean(null));
    }

    @Test
    void cleanToNullTreatsAWhitespaceOnlyValueAsAbsent() {
        assertNull(ImportText.cleanToNull("   \n"));
        assertNull(ImportText.cleanToNull(""));
        assertEquals("KRFB", ImportText.cleanToNull(" KRFB "));
    }
}
