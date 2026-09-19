package com.fist.rmms_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @EnableScheduling drives the Monitoring module's periodic collectors
 *  (SystemMetricsService, HealthCheckService, MetricsRetentionService). */
@SpringBootApplication
@EnableScheduling
public class RmmsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(RmmsBackendApplication.class, args);
	}

}
