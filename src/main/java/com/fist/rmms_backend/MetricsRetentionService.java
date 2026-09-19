package com.fist.rmms_backend;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily prune of the monitoring tables. Deletes are done here, off the write
 * path, rather than on every insert — {@link ApiMetricsFilter} fires on
 * every API call and a DELETE on that path would tax exactly the requests
 * being measured.
 */
@Service
public class MetricsRetentionService {

    private final ApiMetricsService apiMetrics;
    private final LayerMetricsService layerMetrics;
    private final SystemMetricsService systemMetrics;
    private final HealthCheckService healthChecks;

    public MetricsRetentionService(ApiMetricsService apiMetrics, LayerMetricsService layerMetrics,
                                    SystemMetricsService systemMetrics, HealthCheckService healthChecks) {
        this.apiMetrics = apiMetrics;
        this.layerMetrics = layerMetrics;
        this.systemMetrics = systemMetrics;
        this.healthChecks = healthChecks;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void purge() {
        apiMetrics.purge(14);
        layerMetrics.purge(14);
        systemMetrics.purge(30);
        healthChecks.purge(30);
    }
}
