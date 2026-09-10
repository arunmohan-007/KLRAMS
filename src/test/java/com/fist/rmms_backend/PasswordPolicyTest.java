package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The password rules, checked against the passwords people actually pick.
 *
 * <p>A policy like this fails in two directions and only one of them is loud. Letting
 * "kerala123" through is silent — nobody notices until an account is taken. Rejecting
 * a perfectly good passphrase is noisy but sends staff back to something worse. So
 * these pin both sides: the guessable shapes are refused, and ordinary strong
 * passwords are not.
 */
class PasswordPolicyTest {

    private static void rejected(String password, String username) {
        assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate(password, username),
                "should have been rejected: " + password);
    }

    private static void accepted(String password, String username) {
        assertDoesNotThrow(() -> PasswordPolicy.validate(password, username),
                "should have been accepted: " + password);
    }

    @Test
    void tooShortOrMissing() {
        rejected(null, "rmms.admin");
        rejected("", "rmms.admin");
        rejected("Shrt1!", "rmms.admin");          // 6 chars
        rejected("Sevench", "rmms.admin");         // 7 chars — one under the floor
    }

    @Test
    void theClassicsAreRefused() {
        rejected("password", null);
        rejected("Password", null);                // case must not rescue it
        rejected("PASSWORD123", null);
        rejected("12345678", null);
        rejected("qwertyuiop", null);
        rejected("letmein123", null);
        rejected("admin123", null);
        rejected("changeme", null);
    }

    @Test
    void repeatedCharactersAndSequences() {
        rejected("aaaaaaaa", null);
        rejected("11111111", null);
        rejected("abcdefgh", null);                // straight alphabet run
        rejected("hgfedcba", null);                // ...and backwards
        rejected("23456789", null);
        rejected("asdfghjkl", null);               // keyboard row
    }

    /** The ones a generic leaked-password list would miss but a local attacker tries first. */
    @Test
    void siteWordsAreRefusedEvenWhenDecorated() {
        rejected("kerala123", null);
        rejected("Kerala@2024", null);             // digits/punctuation stripped -> "kerala"
        rejected("KLRAMS2026", null);
        rejected("rmms@1234", null);
        rejected("khri-2026", null);
    }

    @Test
    void passwordMustNotContainTheUsername() {
        rejected("rmms.admin2026", "rmms.admin");
        rejected("XXrmms.adminXX", "rmms.admin");
        rejected("SajeevKumar99", "sajeevkumar");  // case-insensitive
    }

    /** A two-letter username must not blanket-reject every password containing those letters. */
    @Test
    void veryShortUsernamesDoNotMatchTooEagerly() {
        accepted("MonsoonLedger42", "ab");
        accepted("QuarryBridge77x", "jo");
    }

    @Test
    void goodPasswordsAreAccepted() {
        accepted("MonsoonLedger42", "rmms.admin");
        accepted("brittle-copper-lantern", "rmms.admin");
        accepted("Th1ruvananthapuram!", "sajeev");
        accepted("correct horse battery", "admin.two");
        accepted("9xQm2vLp", "rmms.admin");        // exactly the 8-char floor
    }

    /** "kerala" is refused, but a longer phrase merely containing it is not. */
    @Test
    void siteWordCheckIsExactStemNotSubstring() {
        accepted("keralaroadsledger", null);
        accepted("HighwayLedger2026", null);
    }
}
