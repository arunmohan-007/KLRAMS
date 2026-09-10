package com.fist.rmms_backend;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules a new password has to pass, in one place so the three ways a password
 * can be set (admin creates an account, admin resets one, user changes their own)
 * cannot drift apart.
 *
 *  WHY THIS EXISTS. Length alone was the whole policy, which let through exactly
 *  the passwords an attacker tries first — "password", "12345678", "kerala123".
 *  That mattered more here than it looks, because the sign-in throttle is keyed
 *  on client IP: an attacker spreading guesses across many IPs never trips it
 *  (see {@link LoginAttemptService}, which now also throttles per username). A
 *  guessable password plus a distributed guesser is the realistic path to a
 *  SUPER_ADMIN account, so the two fixes are deliberately paired.
 *
 *  DELIBERATELY NOT A COMPLEXITY RULE. No "one upper, one digit, one symbol"
 *  requirement — that pushes people to "Password@1" (which this class rejects)
 *  and is no longer recommended by NIST SP 800-63B. The checks here are aimed at
 *  guessability instead: known-common passwords, single repeated characters,
 *  keyboard/digit runs, and the words this particular office will reach for.
 */
final class PasswordPolicy {

    private PasswordPolicy() {}

    /** Minimum length. Raised 6 -> 8 in the 2026-07 hardening pass. */
    static final int MIN_LEN = 8;

    /**
     * The passwords guessed first. Not a full leaked-credential corpus — a top-10k
     * list would mean shipping and loading a resource file for a login that a
     * handful of staff use. This covers the common shapes; the pattern checks in
     * {@link #validate} catch most of the rest.
     */
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
            "password", "password1", "password123", "passw0rd", "p@ssword", "p@ssw0rd",
            "12345678", "123456789", "1234567890", "123123123", "11223344", "12341234",
            "qwertyui", "qwerty123", "qwertyuiop", "asdfghjkl", "zxcvbnm123", "1qaz2wsx",
            "iloveyou", "sunshine", "princess", "football", "baseball", "superman",
            "welcome1", "welcome123", "letmein1", "letmein123", "trustno1", "starwars",
            "admin123", "administrator", "adminadmin", "root1234", "toor1234",
            "abc12345", "abcd1234", "a1b2c3d4", "test1234", "temp1234", "changeme",
            "default1", "guest123", "user1234", "login123", "secret123", "master123",
            "monkey123", "dragon123", "shadow123", "michael1", "jennifer1", "computer1",
            "india123", "bharat123", "incredible1"));

    /**
     * Words specific to this deployment. Staff reach for the organisation's own
     * name first, and none of these would appear in a generic leaked-password
     * list — but they are the first thing anyone targeting KLRAMS would try.
     */
    private static final List<String> SITE_WORDS = List.of(
            "klrams", "rmms", "kerala", "keralapwd", "pwd", "khri", "kpwd",
            "roads", "road", "highway", "admin", "adminpwd", "publicworks");

    /** Runs a slice of which is a giveaway ("qwerty", "456789", "abcdef"). */
    private static final List<String> RUNS = List.of(
            "abcdefghijklmnopqrstuvwxyz",
            "01234567890",
            "qwertyuiop", "asdfghjkl", "zxcvbnm");

    /**
     * Throws IllegalArgumentException with a message safe to show the user
     * (ApiErrors.safe passes IllegalArgumentException text straight through).
     *
     * @param password the proposed password, checked as typed
     * @param username the account it is for, so the password cannot simply be it;
     *                 may be null when the caller does not know it
     */
    static void validate(String password, String username) {
        if (password == null || password.length() < MIN_LEN)
            throw new IllegalArgumentException("Password must be at least " + MIN_LEN + " characters");

        String p = password.toLowerCase(Locale.ROOT);

        if (COMMON.contains(p))
            throw new IllegalArgumentException(
                    "That password is one of the most commonly used ones — please choose something less guessable");

        // "aaaaaaaa", "11111111"
        if (p.chars().distinct().count() == 1)
            throw new IllegalArgumentException("Password must not be the same character repeated");

        // A straight run off the keyboard or the number row, forwards or backwards.
        String letters = p.replaceAll("[^a-z0-9]", "");
        if (letters.length() >= MIN_LEN) {
            String reversed = new StringBuilder(letters).reverse().toString();
            for (String run : RUNS) {
                if (run.contains(letters) || run.contains(reversed))
                    throw new IllegalArgumentException(
                            "Password must not be a straight keyboard or number sequence");
            }
        }

        // Strip digits/punctuation so "Kerala@2024" is judged as "kerala".
        String stem = p.replaceAll("[^a-z]", "");
        if (!stem.isEmpty()) {
            for (String w : SITE_WORDS) {
                if (stem.equals(w))
                    throw new IllegalArgumentException(
                            "Password must not be based on \"" + w + "\" — it is the first thing an attacker tries here");
            }
            if (COMMON.contains(stem))
                throw new IllegalArgumentException(
                        "That password is one of the most commonly used ones — please choose something less guessable");
        }

        if (username != null) {
            String u = username.trim().toLowerCase(Locale.ROOT);
            // Short usernames would match too eagerly ("ab" inside any word).
            if (u.length() >= 3 && p.contains(u))
                throw new IllegalArgumentException("Password must not contain your username");
        }
    }
}
