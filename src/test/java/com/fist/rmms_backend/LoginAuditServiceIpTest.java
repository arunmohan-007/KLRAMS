package com.fist.rmms_backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client-IP resolution behind a reverse proxy.
 *
 * <p>Two properties have to hold at once, and the production bug came from
 * getting the second right while assuming the first away: the value must be
 * unforgeable by the client (so never read from the LEFT of X-Forwarded-For),
 * and it must actually identify the client (so the number of hops counted in
 * from the right must match the real proxy chain). Taking the last entry against
 * a two-hop chain satisfied the first and broke the second — every visitor
 * resolved to the inner proxy, and one person's five bad passwords locked
 * everyone out.
 */
class LoginAuditServiceIpTest {

    /** setProxyHops is an instance method only because @Value needs one; nothing it touches is state. */
    private static void hops(int n) { new LoginAuditService(null, null).setProxyHops(n); }

    @AfterEach
    void reset() { hops(1); }

    private static MockHttpServletRequest req(String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("172.18.0.5");                 // the docker bridge, as in production
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void oneProxyTakesTheOnlyEntry() {
        hops(1);
        assertEquals("203.0.113.9", LoginAuditService.clientIp(req("203.0.113.9")));
    }

    @Test
    void twoProxiesSkipTheInnerHop() {
        hops(2);
        // Cloudflare appended the client; nginx then appended Cloudflare.
        assertEquals("203.0.113.9", LoginAuditService.clientIp(req("203.0.113.9, 198.51.100.1")));
    }

    /** The regression: one hop configured against a two-hop chain returns the shared proxy. */
    @Test
    void wrongHopCountResolvesToTheProxyAndIsRecognisedAsInternal() {
        hops(1);
        String ip = LoginAuditService.clientIp(req("203.0.113.9, 172.18.0.5"));
        assertEquals("172.18.0.5", ip, "this is the value that collapsed every visitor onto one key");
        assertTrue(LoginAuditService.isInternalAddress(ip),
                "and this is why the lockout now refuses to key on it");
    }

    @Test
    void aForgedPrefixIsIgnored() {
        hops(2);
        // The client sent "9.9.9.9"; CF appended the real address, nginx appended CF.
        assertEquals("203.0.113.9",
                LoginAuditService.clientIp(req("9.9.9.9, 203.0.113.9, 198.51.100.1")),
                "junk on the left must never be reachable");
    }

    @Test
    void moreHopsConfiguredThanSentClampsToTheLeftmost() {
        hops(5);
        assertEquals("203.0.113.9", LoginAuditService.clientIp(req("203.0.113.9")));
    }

    @Test
    void noForwardedHeaderFallsBackToTheSocket() {
        hops(1);
        assertEquals("172.18.0.5", LoginAuditService.clientIp(req(null)));
    }

    @Test
    void internalAddressClassification() {
        assertTrue(LoginAuditService.isInternalAddress("10.1.2.3"));
        assertTrue(LoginAuditService.isInternalAddress("192.168.0.4"));
        assertTrue(LoginAuditService.isInternalAddress("172.16.0.1"));
        assertTrue(LoginAuditService.isInternalAddress("172.31.255.254"));
        assertTrue(LoginAuditService.isInternalAddress("::1"));
        assertTrue(LoginAuditService.isInternalAddress("fd00::1"));
        assertTrue(LoginAuditService.isInternalAddress("::ffff:127.0.0.1"));
        assertTrue(LoginAuditService.isInternalAddress(null));
        assertTrue(LoginAuditService.isInternalAddress("   "));

        assertFalse(LoginAuditService.isInternalAddress("203.0.113.9"));
        assertFalse(LoginAuditService.isInternalAddress("172.15.0.1"));   // just below the private block
        assertFalse(LoginAuditService.isInternalAddress("172.32.0.1"));   // just above it
        assertFalse(LoginAuditService.isInternalAddress("2001:db8::1"));
    }
}
