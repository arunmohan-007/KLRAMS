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
 *  Keyed by IP (not username) so a legitimate user cannot be locked out by
 *  someone else spamming their username; the trade-off is that distributed
 *  brute force from many IPs is not stopped by this layer alone. State is
 *  in-memory — a restart clears all counters, which is fine for a throttle.
 */
@Service
public class LoginAttemptService {

    /** Failures within {@link #WINDOW_MS} needed to trip a lockout. */
    private static final int  MAX_ATTEMPTS = 5;
    /** Failures older than this are forgotten (rolling window). */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** How long an IP stays locked once tripped. */
    private static final long LOCK_MS   = 15 * 60 * 1000L;
    /** Cap on tracked keys so IP rotation cannot exhaust memory. */
    private static final int  MAX_TRACKED = 10_000;

    private static final class Attempt {
        int count;
        long windowStart;
        long lockedUntil;
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** True while this IP is inside its lockout window. */
    public boolean isBlocked(String key) {
        if (key == null) return false;
        Attempt a = attempts.get(key);
        return a != null && System.currentTimeMillis() < a.lockedUntil;
    }

    /** Record a failed sign-in; trips the lockout once the threshold is reached. */
    public void loginFailed(String key) {
        if (key == null) return;
        long now = System.currentTimeMillis();
        if (attempts.size() > MAX_TRACKED) prune(now);
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStart > WINDOW_MS) {   // fresh window
                a = new Attempt();
                a.windowStart = now;
            }
            if (now < a.lockedUntil) return a;                    // already locked — don't extend
            a.count++;
            if (a.count >= MAX_ATTEMPTS) a.lockedUntil = now + LOCK_MS;
            return a;
        });
    }

    /** Clear an IP's counter after a successful sign-in. */
    public void loginSucceeded(String key) {
        if (key != null) attempts.remove(key);
    }

    /** Seconds remaining on the lockout (0 if not locked), for messaging. */
    public long secondsUntilUnlock(String key) {
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
