package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sign-in throttle's two counters.
 *
 * <p>The interesting case is the one the IP counter alone used to miss: a guesser
 * that never repeats an address. Each individual IP stays well under its own
 * threshold, so only the per-account counter can see the attack at all.
 *
 * <p>Addresses here are deliberately from the public documentation ranges
 * (203.0.113.0/24, 198.51.100.0/24) rather than 10.x: the service refuses to
 * rate-limit on a private address, because resolving one means the proxy chain
 * has collapsed every visitor onto a single value. See
 * {@link #aPrivateAddressIsNeverRateLimited}.
 */
class LoginAttemptServiceTest {

    @Test
    void fiveFailuresFromOneIpLocksThatIp() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 4; i++) s.loginFailed("203.0.113.1", "someone");
        assertFalse(s.isBlocked("203.0.113.1", "someone"), "4 failures should not lock yet");
        s.loginFailed("203.0.113.1", "someone");
        assertTrue(s.isBlocked("203.0.113.1", "someone"), "5th failure should lock the IP");
    }

    /** The gap this change was written to close. */
    @Test
    void distributedGuessingAtOneAccountTripsTheAccountCounter() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 14; i++) s.loginFailed("203.0.113." + i, "rmms.admin");   // every guess a new IP
        assertFalse(s.isBlocked("203.0.113.99", "rmms.admin"), "14 should still be under the account threshold");
        s.loginFailed("203.0.113.14", "rmms.admin");
        assertTrue(s.isBlocked("203.0.113.99", "rmms.admin"),
                "15 failures across 15 different IPs must lock the ACCOUNT");
    }

    /** A locked account must not drag unrelated accounts down with it. */
    @Test
    void lockingOneAccountLeavesOthersAlone() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("203.0.113." + i, "rmms.admin");
        assertTrue(s.isBlocked("203.0.113.99", "rmms.admin"));
        assertFalse(s.isBlocked("203.0.113.99", "someone.else"));
    }

    @Test
    void usernameCounterIsCaseInsensitive() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("203.0.113." + i, i % 2 == 0 ? "RMMS.Admin" : "rmms.admin");
        assertTrue(s.isBlocked("203.0.113.99", "rmms.admin"),
                "alternating case must not reset the counter");
    }

    /** Namespacing check: an IP-shaped username must not poison the real address. */
    @Test
    void usernameCannotCollideWithAnIpKey() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 15; i++) s.loginFailed("203.0.113." + i, "203.0.113.1");   // username LOOKS like an IP
        assertTrue(s.isBlocked("198.51.100.7", "203.0.113.1"), "the account should be locked");
        assertFalse(s.isBlocked("203.0.113.1", "different.user"),
                "the real 203.0.113.1 address must not be locked by the lookalike username");
    }

    @Test
    void successClearsBothCounters() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 4; i++) s.loginFailed("203.0.113.1", "rmms.admin");
        s.loginSucceeded("203.0.113.1", "rmms.admin");
        for (int i = 0; i < 4; i++) s.loginFailed("203.0.113.1", "rmms.admin");
        assertFalse(s.isBlocked("203.0.113.1", "rmms.admin"),
                "the counter should have restarted after the successful sign-in");
    }

    @Test
    void missingUsernameIsToleratedAndOnlyIpIsCounted() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 5; i++) s.loginFailed("203.0.113.1", null);
        assertTrue(s.isBlocked("203.0.113.1", null));
        assertFalse(s.isBlocked("203.0.113.2", null));
        assertDoesNotThrow(() -> s.loginFailed(null, "   "));   // blank username, no IP
    }

    /**
     * The production outage this guard was added for: behind a two-hop proxy the
     * resolved "client IP" was the inner proxy's own private address, identical for
     * everyone, so five failures by any one person locked every account on every
     * machine out for fifteen minutes. A private address must never be rate-limited.
     */
    @Test
    void aPrivateAddressIsNeverRateLimited() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 20; i++) s.loginFailed("172.18.0.1", "someone" + i);   // docker bridge
        assertFalse(s.isBlocked("172.18.0.1", "a.new.user"),
                "a shared private address must not lock out unrelated accounts");
        for (int i = 0; i < 20; i++) s.loginFailed("127.0.0.1", "someone" + i);
        assertFalse(s.isBlocked("127.0.0.1", "a.new.user"));
    }

    /** 172.16.0.0/12 is private, but the rest of 172.x is ordinary public space. */
    @Test
    void publicPartOfThe172BlockStillCounts() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 5; i++) s.loginFailed("172.32.5.9", "rmms.admin");
        assertTrue(s.isBlocked("172.32.5.9", "other.user"), "172.32.x is public and must still lock");
    }

    @Test
    void secondsUntilUnlockReportsTheLongerOfTheTwo() {
        LoginAttemptService s = new LoginAttemptService();
        for (int i = 0; i < 5; i++) s.loginFailed("203.0.113.1", "rmms.admin");
        long secs = s.secondsUntilUnlock("203.0.113.1", "rmms.admin");
        // IP lock is 15 min, account lock 5 min; the IP one is the live constraint here.
        assertTrue(secs > 14 * 60 && secs <= 15 * 60, "expected the 15-minute IP lock, got " + secs + "s");
    }
}
