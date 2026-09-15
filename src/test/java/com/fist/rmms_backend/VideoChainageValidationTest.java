package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A road can now carry several video clips, so the catalogue CSV must state the
 * chainage stretch each row's clip covers. These checks guard the mistakes that
 * would otherwise import cleanly and only surface later as a clip that never
 * plays for part of the road, or two clips that silently overlap.
 */
class VideoChainageValidationTest {

    private static String check(String from, String to) throws Exception {
        Method m = VideoService.class.getDeclaredMethod("checkChainage", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, from, to);
    }

    private static void ok(String from, String to) throws Exception {
        assertNull(check(from, to), "should have been accepted: " + from + ".." + to);
    }

    private static void bad(String from, String to, String expectedFragment) throws Exception {
        String problem = check(from, to);
        assertNotNull(problem, "should have been rejected: " + from + ".." + to);
        assertTrue(problem.toLowerCase().contains(expectedFragment.toLowerCase()),
                "message for \"" + from + ".." + to + "\" should mention \"" + expectedFragment + "\" but was: " + problem);
    }

    @Test
    void ordinaryRangesAreFine() throws Exception {
        ok("0", "1200");
        ok("1200.5", "3000");
    }

    @Test
    void missingValuesAreRejected() throws Exception {
        bad(null, "1000", "missing");
        bad("0", null, "missing");
        bad(null, null, "missing");
    }

    @Test
    void nonNumericValuesAreRejected() throws Exception {
        bad("abc", "1000", "non-numeric");
        bad("0", "xyz", "non-numeric");
    }

    @Test
    void negativeValuesAreRejected() throws Exception {
        bad("-100", "500", "negative");
    }

    @Test
    void toNotGreaterThanFromIsRejected() throws Exception {
        bad("1000", "1000", "not greater than");
        bad("1500", "1000", "not greater than");
    }
}
