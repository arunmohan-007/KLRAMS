package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * "What does the server think my address is?" — SUPER_ADMIN only, inherited from
 * the {@code /api/reports/**} rule in {@link SecurityConfig}.
 *
 *  WHY THIS EXISTS. The sign-in lockout keys on {@link LoginAuditService#clientIp},
 *  which counts {@code app.security.proxy-hops} entries in from the right of
 *  X-Forwarded-For. That number has to match the real proxy chain, and the chain
 *  is not visible from the application — it is whatever nginx, Docker and any
 *  edge in front of them are doing. Get it too low and every visitor resolves to
 *  the same inner-proxy address, which is how one person's five bad passwords
 *  once locked the entire user base out for fifteen minutes.
 *
 *  Rather than guess, this echoes the actual headers of the request you just
 *  made, hop by hop, with the resolved value and the lockout state that follows
 *  from it. One call from a normal browser settles the setting.
 *
 *  It reports only on the caller's OWN request — no stored data, no other user's
 *  address — but it is still SUPER_ADMIN-gated, because a verbatim dump of proxy
 *  headers describes the infrastructure in front of the app.
 *
 *  Note this cannot be reached while you are locked out (you would have to sign
 *  in first). It is for TUNING the hop count; the private-address backstop in
 *  {@link LoginAttemptService} is what prevents the lockout in the first place.
 */
@RestController
@RequestMapping("/api/reports")
public class ConnectionDiagnosticsController {

    /** Headers worth echoing verbatim when a proxy chain misbehaves. */
    private static final String[] INTERESTING = {
            "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Proto", "X-Forwarded-Host",
            "X-Forwarded-Port", "Forwarded", "CF-Connecting-IP", "True-Client-IP",
            "X-Client-IP", "Host"
    };

    private final LoginAttemptService attempts;

    public ConnectionDiagnosticsController(LoginAttemptService attempts){ this.attempts = attempts; }

    @GetMapping("/connection")
    public Map<String,Object> connection(HttpServletRequest req, java.security.Principal who){
        Map<String,Object> out = new LinkedHashMap<>();

        String resolved = LoginAuditService.clientIp(req);
        boolean internal = LoginAuditService.isInternalAddress(resolved);
        int configured = LoginAuditService.proxyHops();

        out.put("resolvedClientIp", resolved);
        out.put("resolvedIsPrivate", internal);
        out.put("configuredProxyHops", configured);
        out.put("socketRemoteAddr", req.getRemoteAddr());

        // Every X-Forwarded-For entry, left to right, with how far in from the RIGHT
        // it sits -- that distance IS the proxy-hops value which would select it.
        String xff = req.getHeader("X-Forwarded-For");
        List<Map<String,Object>> hops = new ArrayList<>();
        if(xff != null && !xff.isBlank()){
            String[] parts = xff.split(",");
            for(int i = 0; i < parts.length; i++){
                Map<String,Object> hop = new LinkedHashMap<>();
                hop.put("address", parts[i].trim());
                hop.put("proxyHopsToSelect", parts.length - i);
                hop.put("isPrivate", LoginAuditService.isInternalAddress(parts[i].trim()));
                hop.put("selected", i == Math.max(0, parts.length - configured));
                // Everything left of the rightmost entry that our own proxy appended is
                // client-supplied and may be pure invention.
                hop.put("clientForgeable", i < parts.length - 1);
                hops.add(hop);
            }
        }
        out.put("forwardedForHops", hops);

        Map<String,String> headers = new LinkedHashMap<>();
        for(String h : INTERESTING){
            String v = req.getHeader(h);
            if(v != null) headers.put(h, v);
        }
        out.put("headers", headers);

        // What the throttle would actually do with this address right now.
        String user = who == null ? null : who.getName();
        Map<String,Object> lock = new LinkedHashMap<>();
        lock.put("ipCounterActive", !internal);
        lock.put("currentlyBlocked", attempts.isBlocked(resolved, user));
        lock.put("secondsUntilUnlock", attempts.secondsUntilUnlock(resolved, user));
        out.put("rateLimiting", lock);

        out.put("verdict", verdict(resolved, internal, configured, hops.size()));
        return out;
    }

    /** Plain-language reading of the numbers above, so the fix does not need interpreting. */
    private static String verdict(String resolved, boolean internal, int configured, int hopCount){
        if(hopCount == 0){
            return internal
                ? "No X-Forwarded-For header at all and the socket address is private ("
                  + resolved + "). The proxy is not forwarding the client address: every visitor looks "
                  + "identical to this app. Per-IP rate limiting is DISABLED as a safety net. Set nginx "
                  + "to send X-Forwarded-For (proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for)."
                : "No proxy headers; talking to clients directly. proxy-hops is not used in this case.";
        }
        if(internal){
            return "app.security.proxy-hops=" + configured + " selects " + resolved + ", which is a private "
                 + "address -- the proxy chain is DEEPER than configured, so every visitor resolves to the "
                 + "same value. Per-IP rate limiting is currently DISABLED to avoid locking all users out. "
                 + "Pick the hop from 'forwardedForHops' that is your real public address and set "
                 + "app.security.proxy-hops to its 'proxyHopsToSelect' value (there are " + hopCount
                 + " hop(s) in this request), then restart.";
        }
        return "app.security.proxy-hops=" + configured + " selects " + resolved + ", a public address. "
             + "If that is the address this browser really has on the internet, the setting is correct and "
             + "per-IP rate limiting is working. If it is one of your own proxies instead, increase "
             + "app.security.proxy-hops until 'resolvedClientIp' is your own address.";
    }
}
