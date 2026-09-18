package com.fist.rmms_backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diagnostic has one job: tell a SUPER_ADMIN which proxy-hops value to set.
 * These pin the two readings that matter — the misconfigured chain (where the
 * answer must be actionable, not just raw data) and the correct one.
 */
class ConnectionDiagnosticsControllerTest {

    private final ConnectionDiagnosticsController ctl =
            new ConnectionDiagnosticsController(new LoginAttemptService());

    private static void hops(int n){ new LoginAuditService(null, null).setProxyHops(n); }

    @AfterEach
    void reset(){ hops(1); }

    private static MockHttpServletRequest req(String xff){
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("172.18.0.5");
        if(xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> hopsOf(Map<String,Object> out){
        return (List<Map<String,Object>>) out.get("forwardedForHops");
    }

    /** The production shape: two hops, one configured. */
    @Test
    void reportsTheMisconfiguredChainAndNamesTheFix(){
        hops(1);
        Map<String,Object> out = ctl.connection(req("203.0.113.9, 172.18.0.5"), null);

        assertEquals("172.18.0.5", out.get("resolvedClientIp"));
        assertEquals(Boolean.TRUE, out.get("resolvedIsPrivate"));

        List<Map<String,Object>> hops = hopsOf(out);
        assertEquals(2, hops.size());
        // The real client is the left entry, and selecting it needs proxy-hops=2.
        assertEquals("203.0.113.9", hops.get(0).get("address"));
        assertEquals(2, hops.get(0).get("proxyHopsToSelect"));
        assertEquals(Boolean.FALSE, hops.get(0).get("isPrivate"));
        assertEquals(Boolean.FALSE, hops.get(0).get("selected"));
        assertEquals(Boolean.TRUE, hops.get(1).get("selected"));

        @SuppressWarnings("unchecked")
        Map<String,Object> rl = (Map<String,Object>) out.get("rateLimiting");
        assertEquals(Boolean.FALSE, rl.get("ipCounterActive"), "a private address must not be rate-limited");

        assertTrue(((String) out.get("verdict")).contains("DEEPER than configured"));
    }

    @Test
    void reportsACorrectlyConfiguredChain(){
        hops(2);
        Map<String,Object> out = ctl.connection(req("203.0.113.9, 172.18.0.5"), null);

        assertEquals("203.0.113.9", out.get("resolvedClientIp"));
        assertEquals(Boolean.FALSE, out.get("resolvedIsPrivate"));

        @SuppressWarnings("unchecked")
        Map<String,Object> rl = (Map<String,Object>) out.get("rateLimiting");
        assertEquals(Boolean.TRUE, rl.get("ipCounterActive"));
        assertEquals(Boolean.FALSE, rl.get("currentlyBlocked"));

        assertTrue(((String) out.get("verdict")).contains("a public address"));
    }

    /** A proxy that forwards nothing is a distinct failure and needs a distinct answer. */
    @Test
    void reportsAProxyThatForwardsNoAddressAtAll(){
        hops(1);
        Map<String,Object> out = ctl.connection(req(null), null);

        assertEquals("172.18.0.5", out.get("resolvedClientIp"));
        assertTrue(hopsOf(out).isEmpty());
        assertTrue(((String) out.get("verdict")).contains("not forwarding the client address"));
    }

    @Test
    void marksClientSuppliedEntriesAsForgeable(){
        hops(2);
        Map<String,Object> out = ctl.connection(req("9.9.9.9, 203.0.113.9, 198.51.100.1"), null);

        List<Map<String,Object>> hops = hopsOf(out);
        assertEquals(Boolean.TRUE,  hops.get(0).get("clientForgeable"), "9.9.9.9 came from the client");
        assertEquals(Boolean.FALSE, hops.get(2).get("clientForgeable"), "only our own proxy writes the last entry");
        assertEquals("203.0.113.9", out.get("resolvedClientIp"));
    }
}
