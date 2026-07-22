package com.fist.rmms_backend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Short-circuits a sign-in attempt from a locked-out IP before Spring Security
 * verifies the password, so a brute-force run stops burning attempts. Sits just
 * ahead of the username/password filter in the chain (wired in
 * {@link SecurityConfig}); the counting itself lives in {@link LoginAttemptService}.
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
                && attempts.isBlocked(LoginAuditService.clientIp(req))) {
            res.sendRedirect(req.getContextPath() + "/login.html?locked");
            return;
        }
        chain.doFilter(req, res);
    }
}
