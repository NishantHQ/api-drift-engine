package com.enterprise.apidrift.config;

import com.enterprise.apidrift.entity.DiffAuditRun;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Custom health indicator that reports on vendor polling staleness.
 * Flags any active vendor whose most recent successful diff run
 * is older than 2x its cron interval, indicating a potential issue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorPollingHealthIndicator implements HealthIndicator {

    private final VendorConfigRepository vendorRepo;
    private final DiffAuditRunRepository auditRepo;

    @Override
    public Health health() {
        List<VendorConfig> activeVendors = vendorRepo.findByIsActiveTrue();
        if (activeVendors.isEmpty()) {
            return Health.up()
                    .withDetail("message", "No active vendors configured")
                    .build();
        }

        Map<String, Object> vendorDetails = new LinkedHashMap<>();
        boolean allHealthy = true;

        for (VendorConfig vendor : activeVendors) {
            Optional<DiffAuditRun> lastRun = auditRepo
                    .findTopByVendorIdOrderByExecutedAtDesc(vendor.getId());

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("vendorName", vendor.getVendorName());

            if (lastRun.isPresent()) {
                DiffAuditRun run = lastRun.get();
                long minutesSinceLastRun = Duration.between(
                        run.getExecutedAt(), OffsetDateTime.now()).toMinutes();
                detail.put("lastRunStatus", run.getStatus().name());
                detail.put("lastRunAt", run.getExecutedAt().toString());
                detail.put("minutesSinceLastRun", minutesSinceLastRun);

                // Flag if last run is older than ~2 days (typical max cron interval)
                if (minutesSinceLastRun > 2880) {
                    detail.put("stale", true);
                    allHealthy = false;
                } else {
                    detail.put("stale", false);
                }
            } else {
                // No diff run yet — the scheduler simply hasn't polled this
                // vendor since registration. That's "pending", not a health
                // failure; marking it DOWN would block the deploy health check
                // on a fresh deploy (the vendor can't have run yet).
                detail.put("lastRunStatus", "NEVER_RUN");
                detail.put("stale", false);
            }

            vendorDetails.put("vendor-" + vendor.getId(), detail);
        }

        return allHealthy
                ? Health.up().withDetail("vendors", vendorDetails).build()
                : Health.down().withDetail("vendors", vendorDetails).build();
    }
}
