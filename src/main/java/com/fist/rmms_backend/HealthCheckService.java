package com.fist.rmms_backend;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Scheduled PostgreSQL / PostGIS health checks for the Monitoring dashboard.
 *
 * <p>This deployment has no GeoServer or standalone tile server to ping —
 * see the "Rendering" notes in {@code CLAUDE.md}: every map layer is served
 * straight out of PostGIS by this application itself (MVT for vectors, a
 * hand-rolled PNG pyramid for drone rasters). So "map/tile service health"
 * here means "can this app still turn PostGIS geometry into a tile
 * quickly", checked with a representative query against the busiest
 * geometry table ({@code roads}) rather than an HTTP self-ping — that also
 * sidesteps needing an authenticated session to probe an endpoint that
 * sits behind the login wall.
 *
 * <p>{@code health_snapshots} carries up/down + latency for three
 * components (DATABASE, POSTGIS, MAP_QUERY); {@code db_stats} carries the
 * numeric trend (connection usage, long-running queries, database size).
 * Both are written together every run so a dashboard reload sees a
 * consistent picture.
 */
@Service
public class HealthCheckService {

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public HealthCheckService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void init() { ensureSchema(); }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS health_snapshots (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "component TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "latency_ms INT, " +
                "detail TEXT, " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_health_snapshots_created_at ON health_snapshots(created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_health_snapshots_component ON health_snapshots(component, created_at DESC)");

        jdbc.execute("CREATE TABLE IF NOT EXISTS db_stats (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "active_connections INT, " +
                "max_connections INT, " +
                "longest_query_sec NUMERIC(10,1), " +
                "db_size_bytes BIGINT, " +
                "pool_active INT, " +
                "pool_idle INT, " +
                "pool_total INT, " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_db_stats_created_at ON db_stats(created_at DESC)");
    }

    @Scheduled(fixedRate = 120_000, initialDelay = 8_000)
    public void runChecks() {
        checkDatabase();
        checkPostgis();
        checkMapQuery();
        collectDbStats();
    }

    private void snapshot(String component, String status, Long latencyMs, String detail) {
        try {
            jdbc.update("INSERT INTO health_snapshots(component, status, latency_ms, detail) VALUES (?,?,?,?)",
                    component, status, latencyMs, detail);
        } catch (Exception e) {
            System.out.println("[HealthCheckService] failed to record " + component + ": " + e.getMessage());
        }
    }

    private void checkDatabase() {
        long start = System.currentTimeMillis();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            snapshot("DATABASE", "UP", System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            snapshot("DATABASE", "DOWN", null, e.getMessage());
        }
    }

    private void checkPostgis() {
        long start = System.currentTimeMillis();
        try {
            String v = jdbc.queryForObject("SELECT PostGIS_Version()", String.class);
            snapshot("POSTGIS", "UP", System.currentTimeMillis() - start, v);
        } catch (Exception e) {
            snapshot("POSTGIS", "DOWN", null, e.getMessage());
        }
    }

    private void checkMapQuery() {
        long start = System.currentTimeMillis();
        try {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM roads", Integer.class);
            snapshot("MAP_QUERY", "UP", System.currentTimeMillis() - start, count + " road features");
        } catch (Exception e) {
            snapshot("MAP_QUERY", "DOWN", null, e.getMessage());
        }
    }

    private void collectDbStats() {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT " +
                    "(SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()) AS active_connections, " +
                    "(SELECT setting::int FROM pg_settings WHERE name = 'max_connections') AS max_connections, " +
                    "(SELECT COALESCE(EXTRACT(EPOCH FROM max(now() - query_start)), 0) FROM pg_stat_activity " +
                    " WHERE datname = current_database() AND state = 'active') AS longest_query_sec, " +
                    "pg_database_size(current_database()) AS db_size_bytes");

            int poolActive = -1, poolIdle = -1, poolTotal = -1;
            if (dataSource instanceof HikariDataSource hikari) {
                var pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    poolActive = pool.getActiveConnections();
                    poolIdle = pool.getIdleConnections();
                    poolTotal = pool.getTotalConnections();
                }
            }

            jdbc.update("INSERT INTO db_stats(active_connections, max_connections, longest_query_sec, " +
                            "db_size_bytes, pool_active, pool_idle, pool_total) VALUES (?,?,?,?,?,?,?)",
                    row.get("active_connections"), row.get("max_connections"), row.get("longest_query_sec"),
                    row.get("db_size_bytes"), poolActive, poolIdle, poolTotal);
        } catch (Exception e) {
            System.out.println("[HealthCheckService] db stats collection failed: " + e.getMessage());
        }
    }

    public Map<String, String> latestStatus() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DISTINCT ON (component) component, status, latency_ms, detail, created_at " +
                "FROM health_snapshots ORDER BY component, created_at DESC");
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : rows) out.put((String) r.get("component"), (String) r.get("status"));
        return out;
    }

    public List<Map<String, Object>> latestSnapshots() {
        return jdbc.queryForList(
                "SELECT DISTINCT ON (component) component, status, latency_ms, detail, created_at " +
                "FROM health_snapshots ORDER BY component, created_at DESC");
    }

    public Map<String, Object> latestDbStats() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM db_stats ORDER BY created_at DESC LIMIT 1");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> dbStatsSeries(int minutes) {
        return jdbc.queryForList(
                "SELECT active_connections, max_connections, longest_query_sec, created_at FROM db_stats " +
                "WHERE created_at > now() - (? || ' minutes')::interval ORDER BY created_at", minutes);
    }

    void purge(int retainDays) {
        jdbc.update("DELETE FROM health_snapshots WHERE created_at < now() - (? || ' days')::interval", retainDays);
        jdbc.update("DELETE FROM db_stats WHERE created_at < now() - (? || ' days')::interval", retainDays);
    }
}
