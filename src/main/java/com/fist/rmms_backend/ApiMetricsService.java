package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

/**
 * Backend API call log for the Monitoring &amp; Health Dashboard
 * ({@link MonitorController}, {@code /monitor.html}, SUPER_ADMIN only).
 *
 * <p>Every {@code /api/**} call (excluding tile endpoints, which are high-volume
 * and timed instead through {@link HealthCheckService}'s representative probe,
 * and {@code /api/monitor/**} itself, to avoid the dashboard's own polling
 * flooding its own log) is timed and recorded by {@link ApiMetricsFilter}.
 *
 * <p>Follows the JdbcTemplate house style — no JPA, schema created on startup.
 */
@Service
public class ApiMetricsService {

    private final JdbcTemplate jdbc;

    public ApiMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() { ensureSchema(); }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_metrics (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "endpoint TEXT NOT NULL, " +
                "method TEXT NOT NULL, " +
                "status INT, " +
                "duration_ms INT NOT NULL, " +
                "error TEXT, " +
                "username TEXT, " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_metrics_created_at ON api_metrics(created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_metrics_endpoint ON api_metrics(endpoint)");
    }

    /** Never throws — a monitoring write must not affect the request it is timing. */
    public void record(String endpoint, String method, int status, long durationMs, String error, String username) {
        try {
            jdbc.update("INSERT INTO api_metrics(endpoint, method, status, duration_ms, error, username) " +
                            "VALUES (?,?,?,?,?,?)",
                    endpoint, method, status, durationMs, error, username);
        } catch (Exception e) {
            System.out.println("[ApiMetricsService] failed to record " + endpoint + ": " + e.getMessage());
        }
    }

    public Map<String, Object> summary(int hours) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COALESCE(AVG(duration_ms),0)::numeric(10,1) AS avg_ms, " +
                "COUNT(*) AS total, " +
                "COALESCE(SUM(CASE WHEN status >= 500 OR status IS NULL THEN 1 ELSE 0 END),0) AS errors " +
                "FROM api_metrics WHERE created_at > now() - (? || ' hours')::interval", hours);
        long total = ((Number) row.get("total")).longValue();
        long errors = ((Number) row.get("errors")).longValue();
        row.put("error_rate_pct", total == 0 ? 0.0 : Math.round(errors * 1000.0 / total) / 10.0);
        row.put("requests_per_min", total == 0 ? 0.0 : Math.round(total * 10.0 / (hours * 60)) / 10.0);
        return row;
    }

    public List<Map<String, Object>> topSlow(int hours, int limit) {
        return jdbc.queryForList(
                "SELECT endpoint, method, COUNT(*) AS calls, " +
                "AVG(duration_ms)::numeric(10,1) AS avg_ms, MAX(duration_ms) AS max_ms, " +
                "COALESCE(SUM(CASE WHEN status >= 500 OR status IS NULL THEN 1 ELSE 0 END),0) AS errors " +
                "FROM api_metrics WHERE created_at > now() - (? || ' hours')::interval " +
                "GROUP BY endpoint, method ORDER BY avg_ms DESC LIMIT ?", hours, limit);
    }

    public List<Map<String, Object>> recentErrors(int hours, int limit) {
        return jdbc.queryForList(
                "SELECT endpoint, method, status, duration_ms, error, username, created_at " +
                "FROM api_metrics WHERE created_at > now() - (? || ' hours')::interval " +
                "AND (status >= 500 OR status IS NULL OR error IS NOT NULL) " +
                "ORDER BY created_at DESC LIMIT ?", hours, limit);
    }

    /** Per-minute request count + average duration, for the response-time bar chart. */
    public List<Map<String, Object>> series(int minutes) {
        return jdbc.queryForList(
                "SELECT date_trunc('minute', created_at) AS bucket, " +
                "COUNT(*) AS calls, AVG(duration_ms)::numeric(10,1) AS avg_ms " +
                "FROM api_metrics WHERE created_at > now() - (? || ' minutes')::interval " +
                "GROUP BY bucket ORDER BY bucket", minutes);
    }

    void purge(int retainDays) {
        jdbc.update("DELETE FROM api_metrics WHERE created_at < now() - (? || ' days')::interval", retainDays);
    }
}
