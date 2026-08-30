package com.fist.rmms_backend;

/**
 * Whitespace cleanup for values arriving from an upload.
 *
 * <h2>Why this exists</h2>
 * A value that differs from another only by invisible characters is the worst
 * kind of data error: every dashboard groups on the stored string, so
 * {@code "PWD Maintenanace"} and {@code "PWD Maintenanace\n"} become two rows
 * for one owner — and nobody can see why, because the difference does not
 * render. It was found in exactly that form on one A/B pair of MC Road, a
 * newline typed into a spreadsheet cell (Alt+Enter) and copied to both
 * carriageways.
 *
 * Trimming belongs HERE, at the door, and not in the queries that read the data:
 * a dashboard that trims is hiding the problem from the only people who can fix
 * it, and has to be remembered in every query forever. Cleaned once on import,
 * the stored value is simply right.
 *
 * <h2>What it does and deliberately does not do</h2>
 * Leading and trailing whitespace only. That covers what upload artifacts
 * actually produce — trailing spaces from a fixed-width DBF field, a newline
 * from a spreadsheet cell, a non-breaking space or a BOM from a copy-paste.
 *
 * It does NOT touch anything inside the value: internal spacing can be
 * meaningful (an address, a road name), and collapsing it would quietly damage
 * good data to tidy up bad. It does NOT correct spelling either — a genuine
 * misspelling stays visible in the breakdowns, which is how it gets noticed and
 * corrected at source.
 *
 * {@link String#trim()} alone is not enough: it stops at U+0020, so a
 * non-breaking space (U+00A0) or a byte-order mark (U+FEFF) survives it, and
 * both are routine in spreadsheet exports.
 */
final class ImportText {

    private ImportText() {}

    private static final char NBSP = ' ';
    private static final char BOM  = '﻿';

    private static boolean strippable(char c) {
        return Character.isWhitespace(c) || c == NBSP || c == BOM;
    }

    /** {@code s} with leading/trailing whitespace, NBSP and BOM removed; null stays null. */
    static String clean(String s) {
        if (s == null) return null;
        int a = 0, b = s.length();
        while (a < b && strippable(s.charAt(a))) a++;
        while (b > a && strippable(s.charAt(b - 1))) b--;
        return (a == 0 && b == s.length()) ? s : s.substring(a, b);
    }

    /** {@link #clean} but an all-whitespace value becomes null rather than "". */
    static String cleanToNull(String s) {
        String t = clean(s);
        return (t == null || t.isEmpty()) ? null : t;
    }
}
