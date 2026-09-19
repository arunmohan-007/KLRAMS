package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

/**
 * GIS layer load performance log — how long each map layer took to load in
 * the browser, and whether it succeeded. Fed by the viewer itself
 * ({@code js/44-monitor-telemetry.js}), which times MapLibre source
 * loads (vector/raster tiles) and the plain GeoJSON {@code fetch()} loaders,
 * and posts to {@code POST /api/monitor/layer-metric}. Read by
 * {@link MonitorController} for {@code /monitor.html}.
 *
 * <p>Follows the JdbcTemplate house style — no JPA, schema created on startup.
 */
@Service
public class LayerMetricsService {

    private final JdbcTemplate jdbc;

    public LayerMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() { ensureSchema(); }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS layer_metrics (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "layer_name TEXT NOT NULL, " +
                "duration_ms INT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "feature_count INT, " +
                "username TEXT, " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_layer_metrics_created_at ON layer_metrics(created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_layer_metrics_layer ON layer_metrics(layer_name)");
    }

    public void record(String layerName, int durationMs, String status, Integer featureCount, String username) {
        try {
            jdbc.update("INSERT INTO layer_metrics(layer_name, duration_ms, status, feature_count, username) " +
                            "VALUES (?,?,?,?,?)",
                    layerName, durationMs, status, featureCount, username);
        } catch (Exception e) {
            System.out.println("[LayerMetricsService] failed to record " + layerName + ": " + e.getMessage());
        }
    }

    public List<Map<String, Object>> avgByLayer(int hours) {
        return jdbc.queryForList(
                "SELECT layer_name, COUNT(*) AS loads, AVG(duration_ms)::numeric(10,1) AS avg_ms, " +
                "MAX(duration_ms) AS max_ms, " +
                "COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failures " +
                "FROM layer_metrics WHERE created_at > now() - (? || ' hours')::interval " +
                "GROUP BY layer_name ORDER BY avg_ms DESC", hours);
    }

    public List<Map<String, Object>> failedLast24h() {
        return jdbc.queryForList(
                "SELECT layer_name, duration_ms, username, created_at " +
                "FROM layer_metrics WHERE status='FAILED' AND created_at > now() - interval '24 hours' " +
                "ORDER BY created_at DESC LIMIT 200");
    }

    /** 10-minute buckets per layer, top layers by volume only, so the chart stays small. */
    public List<Map<String, Object>> series(int hours, int topLayers) {
        return jdbc.queryForList(
                "WITH top AS (SELECT layer_name FROM layer_metrics " +
                "WHERE created_at > now() - (? || ' hours')::interval " +
                "GROUP BY layer_name ORDER BY COUNT(*) DESC LIMIT ?) " +
                "SELECT to_timestamp(floor(extract(epoch FROM lm.created_at)/600)*600) AS bucket, " +
                "lm.layer_name, AVG(lm.duration_ms)::numeric(10,1) AS avg_ms " +
                "FROM layer_metrics lm JOIN top ON top.layer_name = lm.layer_name " +
                "WHERE lm.created_at > now() - (? || ' hours')::interval " +
                "GROUP BY bucket, lm.layer_name ORDER BY bucket", hours, topLayers, hours);
    }

    void purge(int retainDays) {
        jdbc.update("DELETE FROM layer_metrics WHERE created_at < now() - (? || ' days')::interval", retainDays);
    }
}
