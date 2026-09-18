package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * Login audit trail for KLRAMS — records every successful sign-in and the
 * matching sign-out so a SUPER_ADMIN can review who accessed the system, from
 * where, and for how long.
 *
 *  Each successful form login inserts one row into {@code login_events} with the
 *  username, a snapshot of the account's full name + role, the client IP, the
 *  browser user-agent, the servlet session id and the login timestamp. When the
 *  same session signs out, {@link #recordLogout(String)} stamps {@code logout_at}
 *  on that row so the report can show session length ("login extend").
 *
 *  Follows the JdbcTemplate house style — no JPA. The table is created on
 *  startup via {@link #ensureSchema()}. Reads are exposed by
 *  {@link LoginAuditController} at {@code /api/reports/logins} (SUPER_ADMIN only).
 */
@Service
public class LoginAuditService {

    private final JdbcTemplate jdbc;
    private final UserService users;

    public LoginAuditService(JdbcTemplate jdbc, UserService users){
        this.jdbc = jdbc;
        this.users = users;
    }

    @PostConstruct
    public void init(){ ensureSchema(); }

    void ensureSchema(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS login_events (" +
                "id SERIAL PRIMARY KEY, " +
                "username TEXT NOT NULL, " +
                "full_name TEXT, " +
                "role TEXT, " +
                "session_id TEXT, " +
                "ip TEXT, " +
                "user_agent TEXT, " +
                "login_at TIMESTAMPTZ NOT NULL DEFAULT now(), " +
                "logout_at TIMESTAMPTZ)");
        // Speeds up the "close the open session for this id" update and the report ordering.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_login_events_session ON login_events(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_login_events_login_at ON login_events(login_at DESC)");
    }

    /* ---------------- writes ---------------- */

    /** Record a successful sign-in. Never throws — auditing must not block login. */
    public void recordLogin(String username, HttpServletRequest req, String sessionId){
        try {
            String fullName = null, role = null;
            Map<String,Object> u = users.findByUsername(username);
            if(u != null){ fullName = (String) u.get("full_name"); role = (String) u.get("role"); }
            jdbc.update("INSERT INTO login_events(username, full_name, role, session_id, ip, user_agent, login_at) " +
                            "VALUES (?,?,?,?,?,?, now())",
                    username, fullName, role, sessionId, clientIp(req), userAgent(req));
        } catch(Exception e){
            System.out.println("[LoginAuditService] failed to record login for " + username + ": " + e.getMessage());
        }
    }

    /** Stamp logout time on the still-open row for this session. Never throws. */
    public void recordLogout(String sessionId){
        if(sessionId == null) return;
        try {
            jdbc.update("UPDATE login_events SET logout_at = now() " +
                            "WHERE id = (SELECT id FROM login_events " +
                            "WHERE session_id = ? AND logout_at IS NULL " +
                            "ORDER BY login_at DESC LIMIT 1)",
                    sessionId);
        } catch(Exception e){
            System.out.println("[LoginAuditService] failed to record logout for session " + sessionId + ": " + e.getMessage());
        }
    }

    /* ---------------- reads ---------------- */

    /**
     * Most recent login events first, optionally restricted to a login-time window.
     * {@code session_seconds} is the elapsed time between login and logout (NULL
     * while the session is still active).
     *
     * @param from inclusive lower bound on {@code login_at} (ISO-8601 / date), or null for no lower bound
     * @param to   inclusive upper bound on {@code login_at} (ISO-8601 / date), or null for no upper bound
     */
    public List<Map<String,Object>> list(int limit, String from, String to){
        int lim = Math.max(1, Math.min(limit, 2000));
        StringBuilder sql = new StringBuilder(
                "SELECT id, username, full_name, role, ip, user_agent, login_at, logout_at, " +
                "  CASE WHEN logout_at IS NOT NULL " +
                "       THEN EXTRACT(EPOCH FROM (logout_at - login_at))::bigint END AS session_seconds " +
                "FROM login_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if(from != null && !from.isBlank()){ sql.append(" AND login_at >= ?::timestamptz"); args.add(from.trim()); }
        // A bare date "to" means "the whole of that day", so extend to the next day exclusively.
        if(to != null && !to.isBlank()){
            boolean dateOnly = to.trim().length() <= 10;   // YYYY-MM-DD
            sql.append(dateOnly ? " AND login_at < (?::date + 1)" : " AND login_at <= ?::timestamptz");
            args.add(to.trim());
        }
        sql.append(" ORDER BY login_at DESC LIMIT ").append(lim);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /* ---------------- helpers ---------------- */

    /** How many trusted reverse proxies sit in front of this app — see
     *  {@link #clientIp}. Configured by {@code app.security.proxy-hops}. */
    private static volatile int proxyHops = 1;

    @org.springframework.beans.factory.annotation.Value("${app.security.proxy-hops:1}")
    void setProxyHops(int hops){ proxyHops = Math.max(1, hops); }

    /** The configured hop count, for {@link ConnectionDiagnosticsController} to report. */
    static int proxyHops(){ return proxyHops; }

    /** Best-effort real client IP, honouring a reverse proxy's X-Forwarded-For.
     *
     *  SECURITY: the LEFT-hand entries of X-Forwarded-For are copied verbatim from
     *  whatever the client sent and are fully forgeable. Keying the login lockout /
     *  audit log on the leftmost value (as this once did) let an attacker send a
     *  different X-Forwarded-For on every request and never trip the 5-strike
     *  lockout, and let them stamp any IP into the login_events audit trail. So we
     *  never read from the left — we count in from the RIGHT, which only our own
     *  infrastructure can write.
     *
     *  HOW MANY TO COUNT IN. Each proxy appends the address IT saw, so the
     *  outermost trusted proxy is the one that appended the true client. With N
     *  trusted proxies in front, the client is therefore at {@code len - N}:
     *  one nginx gives {@code [client]}; Cloudflare + nginx gives
     *  {@code [client, cloudflare]}; and a forged prefix only pushes junk further
     *  left, where we never look. Taking the last entry (which is N = 1) against a
     *  TWO-hop chain returns the inner proxy's own address — the same value for
     *  every visitor on earth, which collapses the whole site onto one lockout
     *  counter. That is what {@code app.security.proxy-hops} exists to state.
     *
     *  With no proxy present, X-Forwarded-For is absent and we fall back to the
     *  real socket address. */
    static String clientIp(HttpServletRequest req){
        if(req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if(xff != null && !xff.isBlank()){
            String[] hops = xff.split(",");
            int i = Math.max(0, hops.length - proxyHops);   // count in from the right
            String ip = hops[i].trim();
            if(!ip.isEmpty()) return ip;
        }
        String real = req.getHeader("X-Real-IP");
        if(real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    /** True for a loopback, link-local or RFC1918/ULA address — i.e. an address
     *  that cannot identify a distinct internet client. Resolving one of these as
     *  "the client" means the proxy chain is not configured as
     *  {@code app.security.proxy-hops} claims, and every visitor is landing on the
     *  same value; {@link LoginAttemptService} refuses to rate-limit on it rather
     *  than lock the entire user base out at once. */
    static boolean isInternalAddress(String ip){
        if(ip == null) return true;
        String s = ip.trim().toLowerCase(Locale.ROOT);
        int pct = s.indexOf('%');                    // strip IPv6 zone id
        if(pct >= 0) s = s.substring(0, pct);
        if(s.startsWith("[")) s = s.substring(1, Math.max(1, s.length() - (s.endsWith("]") ? 1 : 0)));
        if(s.isEmpty() || "unknown".equals(s)) return true;
        if(s.startsWith("::ffff:")) s = s.substring(7);          // IPv4-mapped IPv6
        if(s.equals("::1") || s.startsWith("fc") || s.startsWith("fd") || s.startsWith("fe80:")) return true;
        if(s.startsWith("127.") || s.startsWith("10.") || s.startsWith("192.168.")
                || s.startsWith("169.254.") || s.equals("0.0.0.0")) return true;
        if(s.startsWith("172.")){                                 // 172.16.0.0/12 only
            int dot = s.indexOf('.', 4);
            try {
                int b = Integer.parseInt(dot > 0 ? s.substring(4, dot) : s.substring(4));
                return b >= 16 && b <= 31;
            } catch(NumberFormatException ignored){ return false; }
        }
        return false;
    }

    static String userAgent(HttpServletRequest req){
        return req == null ? null : req.getHeader("User-Agent");
    }
}
