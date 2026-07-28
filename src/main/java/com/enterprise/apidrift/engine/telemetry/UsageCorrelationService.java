package com.enterprise.apidrift.engine.telemetry;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Correlates detected breaking changes against internal service telemetry
 * to adjust severity based on actual consumer impact.
 *
 * BRD FR-4 Severity Adjustment:
 *  - Actively consumed (last 30 days) → CRITICAL
 *  - Not consumed by any service → LOW / INFORMATIONAL
 *
 * In production, this queries a real telemetry registry (gateway logs, tracing).
 * The current implementation uses a mock registry for development.
 */
@Slf4j
@Service
public class UsageCorrelationService {

    private final TelemetryRegistry telemetryRegistry;

    public UsageCorrelationService(TelemetryRegistry telemetryRegistry) {
        this.telemetryRegistry = telemetryRegistry;
    }

    /**
     * Correlates each change against known consumers and adjusts severity.
     */
    public List<DetectedChange> correlateWithUsage(Long vendorId, List<DetectedChange> changes) {
        for (DetectedChange change : changes) {
            Set<String> consumingServices = telemetryRegistry.findConsumers(
                    vendorId,
                    change.getEndpointPath(),
                    change.getJsonPointer(),
                    change.getHttpMethod());

            if (!consumingServices.isEmpty()) {
                // Actively consumed → CRITICAL escalation
                if (change.isBreaking()) {
                    change.setSeverity(ChangeSeverity.CRITICAL);
                }
                change.setConsumingService(String.join(", ", consumingServices));
                log.info("Change on {}/{} consumed by {} — severity escalated to CRITICAL",
                        change.getEndpointPath(), change.getJsonPointer(), consumingServices);
            } else {
                // Not consumed → downgrade
                if (change.isBreaking()) {
                    change.setSeverity(ChangeSeverity.LOW);
                    log.info("Change on {}/{} not consumed — severity downgraded to LOW",
                            change.getEndpointPath(), change.getJsonPointer());
                }
            }
        }

        return changes;
    }
}
