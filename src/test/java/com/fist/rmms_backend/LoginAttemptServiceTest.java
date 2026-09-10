package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sign-in throttle's two counters.
 *
 * <p>The interesting case is the one the IP counter alone used to miss: a guesser
 * that never repeats an address. Each individual IP stays well under its own
 * threshold, so only the per-account counter can see the attack at all.
 */
class LoginAttemptServiceTest {

    @Test
    void fiveFailuresFromOneIpLocksThatIp() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 4; i++) s.loginFailed("10.0.0.1", "someone");
        assertFalse(s.isBlocked("10.0.0.1", "someone"), "4 failures should not lock yet");
        s.loginFailed("10.0.0.1", "someone");
        assertTrue(s.isBlocked("10.0.0.1", "someone"), "5th failure should lock the IP");
    }

    /** The gap this change was written to close. */
    @Test
    void distributedGuessingAtOneAccountTripsTheAccountCounter() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 14; i++) s.loginFailed("10.0.0." + i, "rmms.admin");   // every guess a new IP
        assertFalse(s.isBlocked("10.0.0.99", "rmms.admin"), "14 should still be under the account threshold");
        s.loginFailed("10.0.0.14", "rmms.admin");
        assertTrue(s.isBlocked("10.0.0.99", "rmms.admin"),
                "15 failures across 15 different IPs must lock the ACCOUNT");
    }

    /** A locked account must not drag unrelated accounts down with it. */
    @Test
    void lockingOneAccountLeavesOthersAlone() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("10.0.0." + i, "rmms.admin");
        assertTrue(s.isBlocked("10.0.0.99", "rmms.admin"));
        assertFalse(s.isBlocked("10.0.0.99", "someone.else"));
    }

    @Test
    void usernameCounterIsCaseInsensitive() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("10.0.0." + i, i % 2 == 0 ? "RMMS.Admin" : "rmms.admin");
        assertTrue(s.isBlocked("10.0.0.99", "rmms.admin"),
                "alternating case must not reset the counter");
    }

    /** Namespacing check: an IP-shaped username must not poison the real address. */
    @Test
    void usernameCannotCollideWithAnIpKey() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("10.0.0." + i, "10.0.0.1");   // username LOOKS like an IP
        assertTrue(s.isBlocked("192.168.1.1", "10.0.0.1"), "the account should be locked");
        assertFalse(s.isBlocked("10.0.0.1", "different.user"),
                "the real 10.0.0.1 address must not be locked by the lookalike username");
    }

    @Test
    void successClearsBothCounters() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 4; i++) s.loginFailed("10.0.0.1", "rmms.admin");
        s.loginSucceeded("10.0.0.1", "rmms.admin");
        for (int i = 0; i < 4; i++) s.loginFailed("10.0.0.1", "rmms.admin");
        assertFalse(s.isBlocked("10.0.0.1", "rmms.admin"),
                "the counter should have restarted after the successful sign-in");
    }

    @Test
    void missingUsernameIsToleratedAndOnlyIpIsCounted() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 5; i++) s.loginFailed("10.0.0.1", null);
        assertTrue(s.isBlocked("10.0.0.1", null));
        assertFalse(s.isBlocked("10.0.0.2", null));
        assertDoesNotThrow(() -> s.loginFailed(null, "   "));   // blank username, no IP
    }

    @Test
    void secondsUntilUnlockReportsTheLongerOfTheTwo() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 5; i++) s.loginFailed("10.0.0.1", "rmms.admin");
        long secs = s.secondsUntilUnlock("10.0.0.1", "rmms.admin");
        // IP lock is 15 min, account lock 5 min; the IP one is the live constraint here.
        assertTrue(secs > 14 * 60 && secs <= 15 * 60, "expected the 15-minute IP lock, got " + secs + "s");
    }
}
