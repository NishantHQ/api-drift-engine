package com.enterprise.apidrift.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized metric definitions for the API Drift Engine pipeline.
 * Counters and timers are registered once at startup; services inject
 * MeterRegistry directly to record values.
 */
@Configuration
public class MetricsConfig {

    private final MeterRegistry registry;

    public MetricsConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Diff Pipeline ────────────────────────────────────────────

    @Bean
    public Counter diffRunsTotal() {
        return Counter.builder("diff.runs.total")
                .description("Total number of diff runs executed")
                .register(registry);
    }

    @Bean
    public Timer diffRunsDuration() {
        return Timer.builder("diff.runs.duration")
                .description("Duration of diff pipeline execution")
                .register(registry);
    }

    @Bean
    public Counter diffChangesDetected() {
        return Counter.builder("diff.changes.detected")
                .description("Total number of changes detected across all diff runs")
                .register(registry);
    }

    // ── Fetch / Egress ───────────────────────────────────────────

    @Bean
    public Counter fetchRequestsTotal() {
        return Counter.builder("fetch.requests.total")
                .description("Total number of spec fetch requests")
                .register(registry);
    }

    @Bean
    public Timer fetchRequestsDuration() {
        return Timer.builder("fetch.requests.duration")
                .description("Duration of spec fetch requests")
                .register(registry);
    }

    // ── Alert Dispatch ───────────────────────────────────────────

    @Bean
    public Counter alertsDispatchedTotal() {
        return Counter.builder("alerts.dispatched.total")
                .description("Total number of alerts dispatched")
                .register(registry);
    }
}
