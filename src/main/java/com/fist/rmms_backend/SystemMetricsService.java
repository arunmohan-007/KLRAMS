package com.fist.rmms_backend;

import com.sun.management.OperatingSystemMXBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/**
 * Server resource usage (CPU / RAM / disk) for the Monitoring dashboard.
 * Read from the JDK's own {@link OperatingSystemMXBean} — no external
 * process ({@code top}/{@code free}/{@code df}) and no new dependency,
 * and it works the same on the Windows dev box and the Ubuntu droplet.
 *
 * <p>Sampled every minute by {@link #collect()} and written to
 * {@code system_metrics}; {@link MetricsRetentionService} prunes it.
 */
@Service
public class SystemMetricsService {

    private final JdbcTemplate jdbc;
    private final OperatingSystemMXBean os =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public SystemMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() { ensureSchema(); }

    void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_metrics (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "cpu_pct NUMERIC(5,1), " +
                "mem_pct NUMERIC(5,1), " +
                "mem_used_mb BIGINT, " +
                "mem_total_mb BIGINT, " +
                "disk_pct NUMERIC(5,1), " +
                "disk_used_gb NUMERIC(10,1), " +
                "disk_total_gb NUMERIC(10,1), " +
                "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_system_metrics_created_at ON system_metrics(created_at DESC)");
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 5_000)
    public void collect() {
        try {
            double cpuPct = Math.max(0, os.getCpuLoad() * 100);

            long memTotal = os.getTotalMemorySize();
            long memFree = os.getFreeMemorySize();
            long memUsed = memTotal - memFree;
            double memPct = memTotal > 0 ? memUsed * 100.0 / memTotal : 0;

            File appDir = new File(".").getAbsoluteFile();
            long diskTotal = appDir.getTotalSpace();
            long diskUsable = appDir.getUsableSpace();
            long diskUsed = diskTotal - diskUsable;
            double diskPct = diskTotal > 0 ? diskUsed * 100.0 / diskTotal : 0;

            jdbc.update("INSERT INTO system_metrics(cpu_pct, mem_pct, mem_used_mb, mem_total_mb, " +
                            "disk_pct, disk_used_gb, disk_total_gb) VALUES (?,?,?,?,?,?,?)",
                    round1(cpuPct), round1(memPct), memUsed / (1024 * 1024), memTotal / (1024 * 1024),
                    round1(diskPct), round1(diskUsed / 1_073_741_824.0), round1(diskTotal / 1_073_741_824.0));
        } catch (Exception e) {
            System.out.println("[SystemMetricsService] collection failed: " + e.getMessage());
        }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    public Map<String, Object> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM system_metrics ORDER BY created_at DESC LIMIT 1");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> series(int minutes) {
        return jdbc.queryForList(
                "SELECT cpu_pct, mem_pct, disk_pct, created_at FROM system_metrics " +
                "WHERE created_at > now() - (? || ' minutes')::interval ORDER BY created_at", minutes);
    }

    void purge(int retainDays) {
        jdbc.update("DELETE FROM system_metrics WHERE created_at < now() - (? || ' days')::interval", retainDays);
    }
}
