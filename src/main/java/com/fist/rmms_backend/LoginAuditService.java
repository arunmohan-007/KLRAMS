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

    /** Best-effort real client IP, honouring a reverse proxy's X-Forwarded-For.
     *
     *  SECURITY: we trust only the LAST hop in X-Forwarded-For — the address our
     *  own reverse proxy actually saw and appended (nginx sets
     *  {@code X-Forwarded-For = "<client-supplied...>, $remote_addr"}). The
     *  earlier, left-hand entries are copied verbatim from whatever the client
     *  sent and are fully forgeable. Keying the login lockout / audit log on the
     *  leftmost value (as this used to) let an attacker send a different
     *  X-Forwarded-For on every request and never trip the 5-strike lockout, and
     *  let them stamp any IP into the login_events audit trail. Taking the
     *  right-most token closes both, because the client cannot influence what the
     *  trusted proxy appends. With no proxy present, X-Forwarded-For is absent and
     *  we fall back to the real socket address. */
    static String clientIp(HttpServletRequest req){
        if(req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if(xff != null && !xff.isBlank()){
            int comma = xff.lastIndexOf(',');        // last hop = what our proxy saw
            String last = (comma >= 0 ? xff.substring(comma + 1) : xff).trim();
            if(!last.isEmpty()) return last;
        }
        String real = req.getHeader("X-Real-IP");
        if(real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    static String userAgent(HttpServletRequest req){
        return req == null ? null : req.getHeader("User-Agent");
    }
}
