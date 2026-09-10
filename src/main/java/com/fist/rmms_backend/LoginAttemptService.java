package com.fist.rmms_backend;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection for the sign-in form.
 *
 *  Spring Security verifies the password on every POST /login, so without a
 *  throttle an attacker can try passwords as fast as the network allows. This
 *  service counts recent failures per client IP and, once the threshold is hit,
 *  locks that IP out for a cool-off period. {@link LoginAttemptFilter} rejects
 *  POST /login from a locked IP BEFORE the password is ever checked, and
 *  {@link SecurityConfig}'s failure/success handlers feed it the outcome.
 *
 *  TWO COUNTERS, DELIBERATELY DIFFERENT. The IP counter is the strict one (5
 *  failures) and stops a single host hammering the form. On its own it missed
 *  the realistic attack: guesses spread across many IPs against one known
 *  account never reach 5 from any single address, so a distributed run was
 *  unthrottled. The username counter closes that — it follows the account being
 *  guessed at, wherever the guesses come from.
 *
 *  The username threshold is deliberately looser (15, not 5) and its lock
 *  shorter (5 minutes, not 15), because a per-username lock is something a
 *  malicious insider can trigger against a colleague on purpose. Those numbers
 *  keep that nuisance small and self-healing while still cutting a distributed
 *  guesser to ~15 tries per 5 minutes however many IPs it owns — the difference
 *  between hours and years for anything but a top-of-the-list password (which
 *  {@link PasswordPolicy} now refuses outright).
 *
 *  Keys are namespaced ("ip:" / "user:") so the two spaces share one map and one
 *  eviction policy without an IP-shaped username colliding with a real address.
 *  Usernames are lower-cased so alternating case cannot reset the counter.
 *  State is in-memory — a restart clears all counters, which is fine for a
 *  throttle.
 */
@Service
public class LoginAttemptService {

    /** Failures within {@link #WINDOW_MS} needed to trip a lockout for one IP. */
    private static final int  MAX_ATTEMPTS = 5;
    /** Failures within {@link #WINDOW_MS} needed to trip a lockout for one account. */
    private static final int  USER_MAX_ATTEMPTS = 15;
    /** Failures older than this are forgotten (rolling window). */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** How long an IP stays locked once tripped. */
    private static final long LOCK_MS   = 15 * 60 * 1000L;
    /** How long an account stays locked once tripped — short, see the class note. */
    private static final long USER_LOCK_MS = 5 * 60 * 1000L;
    /** Cap on tracked keys so IP rotation cannot exhaust memory. */
    private static final int  MAX_TRACKED = 10_000;

    private static final String IP_PREFIX   = "ip:";
    private static final String USER_PREFIX = "user:";

    private static final class Attempt {
        int count;
        long windowStart;
        long lockedUntil;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** null-safe, case-insensitive account key; null when there is no username to key on. */
    private static String userKey(String username) {
        if (username == null) return null;
        String u = username.trim().toLowerCase(java.util.Locale.ROOT);
        return u.isEmpty() ? null : USER_PREFIX + u;
    }

    /** True while EITHER this IP or this account is inside a lockout window. */
    public boolean isBlocked(String ip, String username) {
        return isKeyBlocked(ip == null ? null : IP_PREFIX + ip) || isKeyBlocked(userKey(username));
    }

    private boolean isKeyBlocked(String key) {
        if (key == null) return false;
        Attempt a = attempts.get(key);
        return a != null && System.currentTimeMillis() < a.lockedUntil;
    }

    /**
     * Record a failed sign-in against both the client IP and the account that was
     * being guessed at. Either counter reaching its own threshold trips a lockout.
     */
    public void loginFailed(String ip, String username) {
        long now = System.currentTimeMillis();
        if (attempts.size() > MAX_TRACKED) prune(now);
        if (ip != null) bump(IP_PREFIX + ip, now, MAX_ATTEMPTS, LOCK_MS);
        String uk = userKey(username);
        if (uk != null) bump(uk, now, USER_MAX_ATTEMPTS, USER_LOCK_MS);
    }

    private void bump(String key, long now, int maxAttempts, long lockMs) {
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStart > WINDOW_MS) {   // fresh window
                a = new Attempt();
                a.windowStart = now;
            }
            if (now < a.lockedUntil) return a;                    // already locked — don't extend
            a.count++;
            if (a.count >= maxAttempts) a.lockedUntil = now + lockMs;
            return a;
        });
    }

    /** Clear both counters after a successful sign-in. */
    public void loginSucceeded(String ip, String username) {
        if (ip != null) attempts.remove(IP_PREFIX + ip);
        String uk = userKey(username);
        if (uk != null) attempts.remove(uk);
    }

    /** Seconds remaining on whichever lockout runs longest (0 if neither), for messaging. */
    public long secondsUntilUnlock(String ip, String username) {
        return Math.max(keySecondsUntilUnlock(ip == null ? null : IP_PREFIX + ip),
                        keySecondsUntilUnlock(userKey(username)));
    }

    private long keySecondsUntilUnlock(String key) {
        Attempt a = key == null ? null : attempts.get(key);
        if (a == null) return 0;
        long ms = a.lockedUntil - System.currentTimeMillis();
        return ms > 0 ? (ms + 999) / 1000 : 0;
    }

    /** Drop entries whose window has closed and whose lock has expired. */
    private void prune(long now) {
        attempts.entrySet().removeIf(e ->
                now - e.getValue().windowStart > WINDOW_MS && now >= e.getValue().lockedUntil);
    }
}
