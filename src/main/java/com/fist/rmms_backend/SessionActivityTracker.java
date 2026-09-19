package com.fist.rmms_backend;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "who is actually active right now" tracker for the Monitoring
 * dashboard's overview cards. {@code login_events} ({@link LoginAuditService})
 * already records sign-in/sign-out, but a signed-in session that has sat idle
 * for hours still shows as open there — there is no per-request heartbeat on
 * it. This fills that gap cheaply: {@link ApiMetricsFilter} touches the
 * signed-in username on every {@code /api/**} call, so "active in the last 5
 * / 15 minutes" reflects real usage, not just an open session.
 *
 * <p>Deliberately not persisted — a restart losing "who was active a minute
 * ago" is fine for a live gauge. Bounded by pruning entries older than an
 * hour on every touch; the user base is small enough (tens to low hundreds
 * of accounts) that this is O(n) over a tiny map, not a concern.
 */
@Component
public class SessionActivityTracker {

    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public void touch(String username) {
        lastSeen.put(username, Instant.now());
        if (lastSeen.size() > 500) prune();
    }

    private void prune() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        lastSeen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    public long activeWithin(int minutes) {
        Instant cutoff = Instant.now().minusSeconds(minutes * 60L);
        return lastSeen.values().stream().filter(t -> t.isAfter(cutoff)).count();
    }
}
