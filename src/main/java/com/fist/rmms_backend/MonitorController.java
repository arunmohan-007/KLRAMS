package com.fist.rmms_backend;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitoring &amp; Health Dashboard — backs {@code /monitor.html}.
 *
 * <p>Every endpoint here, including {@code POST /layer-metric}, is
 * SUPER_ADMIN only — wired in {@link SecurityConfig} the same way as
 * {@code /api/reports/**} (the Login Activity report). This is operational
 * insight into the running system, not day-to-day GIS data, so the viewer
 * only sends layer-load telemetry when the signed-in account is
 * SUPER_ADMIN (see {@code js/44-monitor-telemetry.js}); USER/ADMIN sessions
 * never call this controller at all.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final ApiMetricsService apiMetrics;
    private final LayerMetricsService layerMetrics;
    private final SystemMetricsService systemMetrics;
    private final HealthCheckService healthChecks;
    private final SessionActivityTracker activity;
    private final LoginAuditService loginAudit;

    public MonitorController(ApiMetricsService apiMetrics, LayerMetricsService layerMetrics,
                              SystemMetricsService systemMetrics, HealthCheckService healthChecks,
                              SessionActivityTracker activity, LoginAuditService loginAudit) {
        this.apiMetrics = apiMetrics;
        this.layerMetrics = layerMetrics;
        this.systemMetrics = systemMetrics;
        this.healthChecks = healthChecks;
        this.activity = activity;
        this.loginAudit = loginAudit;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> health = healthChecks.latestStatus();
        boolean allUp = health.values().stream().allMatch("UP"::equals) && !health.isEmpty();

        out.put("systemStatus", allUp ? "OK" : "DEGRADED");
        out.put("health", health);
        out.put("db", healthChecks.latestDbStats());
        out.put("system", systemMetrics.latest());
        out.put("api", apiMetrics.summary(1));
        out.put("activeUsers5m", activity.activeWithin(5));
        out.put("activeUsers15m", activity.activeWithin(15));
        out.put("alerts", alerts());
        return out;
    }

    @GetMapping("/apis")
    public Map<String, Object> apis(@RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", apiMetrics.summary(hours));
        out.put("topSlow", apiMetrics.topSlow(hours, 20));
        out.put("recentErrors", apiMetrics.recentErrors(hours, 50));
        out.put("series", apiMetrics.series(Math.min(hours * 60, 360)));
        return out;
    }

    @GetMapping("/layers")
    public Map<String, Object> layers(@RequestParam(defaultValue = "24") int hours) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("avgByLayer", layerMetrics.avgByLayer(hours));
        out.put("failedLast24h", layerMetrics.failedLast24h());
        out.put("series", layerMetrics.series(Math.min(hours, 24), 6));
        return out;
    }

    @GetMapping("/system")
    public Map<String, Object> system(@RequestParam(defaultValue = "180") int minutes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latest", systemMetrics.latest());
        out.put("series", systemMetrics.series(minutes));
        return out;
    }

    @GetMapping("/db")
    public Map<String, Object> db(@RequestParam(defaultValue = "180") int minutes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshots", healthChecks.latestSnapshots());
        out.put("stats", healthChecks.latestDbStats());
        out.put("series", healthChecks.dbStatsSeries(minutes));
        return out;
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeUsers5m", activity.activeWithin(5));
        out.put("activeUsers15m", activity.activeWithin(15));
        out.put("openSessions", loginAudit.openSessions(24, 100));
        return out;
    }

    /** Fire-and-forget telemetry the viewer posts for its own GIS layer loads. */
    @PostMapping("/layer-metric")
    public void layerMetric(@RequestBody Map<String, Object> body, Authentication auth) {
        String layer = String.valueOf(body.getOrDefault("layer", "unknown"));
        int durationMs = ((Number) body.getOrDefault("durationMs", 0)).intValue();
        String status = String.valueOf(body.getOrDefault("status", "SUCCESS"));
        Integer featureCount = body.get("featureCount") != null ? ((Number) body.get("featureCount")).intValue() : null;
        layerMetrics.record(layer, durationMs, status, featureCount, auth != null ? auth.getName() : null);
    }

    private List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> health = healthChecks.latestStatus();
        health.forEach((component, status) -> {
            if (!"UP".equals(status)) out.add(alert("error", component + " is down"));
        });

        Map<String, Object> sys = systemMetrics.latest();
        Object cpu = sys.get("cpu_pct");
        if (cpu instanceof Number n && n.doubleValue() > 80) out.add(alert("warn", "CPU usage is " + n + "%"));
        Object mem = sys.get("mem_pct");
        if (mem instanceof Number n && n.doubleValue() > 85) out.add(alert("warn", "Memory usage is " + n + "%"));
        Object disk = sys.get("disk_pct");
        if (disk instanceof Number n && n.doubleValue() > 85) out.add(alert("warn", "Disk usage is " + n + "%"));

        Map<String, Object> api = apiMetrics.summary(1);
        Object avgMs = api.get("avg_ms");
        if (avgMs instanceof Number n && n.doubleValue() > 2000) out.add(alert("warn", "Average API response time is " + n + " ms"));
        Object errRate = api.get("error_rate_pct");
        if (errRate instanceof Number n && n.doubleValue() > 5) out.add(alert("warn", "API error rate is " + n + "%"));

        for (Map<String, Object> l : layerMetrics.avgByLayer(1)) {
            Object avg = l.get("avg_ms");
            if (avg instanceof Number n && n.doubleValue() > 3000) {
                out.add(alert("warn", l.get("layer_name") + " layer averaging " + n + " ms to load"));
            }
        }
        return out;
    }

    private Map<String, Object> alert(String level, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("message", message);
        return m;
    }
}
