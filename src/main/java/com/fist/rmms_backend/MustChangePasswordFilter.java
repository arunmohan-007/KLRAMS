package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Enforces the {@code must_change_password} flag on the server.
 *
 *  A freshly-created or admin-reset account is flagged {@code must_change_password
 *  = true}. Until the user actually changes it (POST /api/account/password) every
 *  other {@code /api/**} call is refused with 403, so the temporary/known
 *  password cannot be used to read or write real data. The frontend already
 *  redirects to the change screen via {@code GET /api/me}; this is the matching
 *  server-side gate (defence in depth — the frontend check alone is bypassable).
 *
 *  Allowed while flagged: {@code GET /api/me} (so the UI can detect the state) and
 *  {@code /api/account/**} (so the change itself can be performed). Login, logout
 *  and static pages are not under {@code /api/} and pass through untouched.
 *
 *  Once a request is seen with the flag clear, a session marker short-circuits
 *  the per-request lookup for the rest of that session.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final String SESSION_OK = "pwPolicyOk";

    private final UserService users;
    private final ObjectMapper om = new ObjectMapper();

    public MustChangePasswordFilter(UserService users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String path = req.getServletPath();
        if (path == null || !path.startsWith("/api/") || isAllowed(path)) { chain.doFilter(req, res); return; }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            chain.doFilter(req, res); return;   // unauthenticated — let the authz layer handle it
        }

        HttpSession session = req.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_OK))) {
            chain.doFilter(req, res); return;   // already satisfied earlier this session
        }

        Map<String, Object> u = users.findByUsername(auth.getName());
        boolean mustChange = u != null && Boolean.TRUE.equals(u.get("must_change_password"));
        if (mustChange) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            om.writeValue(res.getWriter(), Map.of(
                    "status", "error",
                    "error", "password_change_required",
                    "message", "You must change your password before continuing."));
            return;
        }
        req.getSession(true).setAttribute(SESSION_OK, Boolean.TRUE);
        chain.doFilter(req, res);
    }

    private static boolean isAllowed(String path) {
        return path.equals("/api/me") || path.startsWith("/api/account/");
    }
}
