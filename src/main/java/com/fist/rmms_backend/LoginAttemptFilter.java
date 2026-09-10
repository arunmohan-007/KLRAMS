package com.fist.rmms_backend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Short-circuits a sign-in attempt from a locked-out IP — or against a locked-out
 * account — before Spring Security verifies the password, so a brute-force run
 * stops burning attempts. Sits just ahead of the username/password filter in the
 * chain (wired in {@link SecurityConfig}); the counting itself lives in
 * {@link LoginAttemptService}.
 *
 * Reading the username with getParameter() here is safe: the login form is
 * ordinary form-urlencoded, so the container parses and caches the parameters,
 * and Spring Security's own filter reads them the same way further down.
 */
public class LoginAttemptFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public LoginAttemptFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(req.getMethod()) && "/login".equals(req.getServletPath())
                && attempts.isBlocked(LoginAuditService.clientIp(req), req.getParameter("username"))) {
            res.sendRedirect(req.getContextPath() + "/login.html?locked");
            return;
        }
        chain.doFilter(req, res);
    }
}
