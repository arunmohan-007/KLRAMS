package com.fist.rmms_backend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Times every {@code /api/**} call for {@link ApiMetricsService} and touches
 * {@link SessionActivityTracker} for the "active users" count on
 * {@code /monitor.html}.
 *
 * <p>Registered as a plain {@code @Component} filter, so Spring Boot's
 * auto-configuration places it after the Spring Security filter chain
 * (which is registered at a very early explicit order) — by the time it
 * runs, {@code req.getUserPrincipal()} is already populated, and the
 * timing naturally excludes login-page redirects and auth rejections that
 * never reach a controller.
 *
 * <p>Vector/raster tile endpoints ({@code /tiles/}) and the monitoring
 * endpoints themselves ({@code /api/monitor/}) are skipped: tiles are
 * called dozens of times per pan and would swamp the log with noise that
 * duplicates the representative probe in {@link HealthCheckService}, and
 * logging the dashboard's own polling would be a feedback loop.
 */
@Component
public class ApiMetricsFilter extends OncePerRequestFilter {

    private final ApiMetricsService metrics;
    private final SessionActivityTracker activity;

    public ApiMetricsFilter(ApiMetricsService metrics, SessionActivityTracker activity) {
        this.metrics = metrics;
        this.activity = activity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(request.getContextPath() + "/api/")
                || uri.contains("/tiles/")
                || uri.contains("/api/monitor/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String error = null;
        try {
            chain.doFilter(req, res);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String username = req.getUserPrincipal() != null ? req.getUserPrincipal().getName() : null;
            metrics.record(req.getServletPath(), req.getMethod(), res.getStatus(), durationMs, error, username);
            if (username != null) activity.touch(username);
        }
    }
}
