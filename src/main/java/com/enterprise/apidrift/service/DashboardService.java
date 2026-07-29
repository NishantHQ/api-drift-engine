package com.enterprise.apidrift.service;

import com.enterprise.apidrift.dto.DashboardResponse;
import com.enterprise.apidrift.engine.telemetry.TelemetryRegistry;
import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates data for the operations dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final VendorConfigRepository vendorRepo;
    private final ChangeFingerprintRepository fingerprintRepo;
    private final DiffAuditRunRepository auditRepo;
    private final TelemetryRegistry telemetryRegistry;

    /**
     * Build the full dashboard summary.
     */
    public DashboardResponse buildDashboard() {
        long totalVendors = vendorRepo.count();
        long activeVendors = vendorRepo.findByIsActiveTrue().size();

        List<ChangeFingerprint> activeFingerprints = fingerprintRepo.findByIsActiveTrue();

        // Group active fingerprints by vendor for vendor count
        Set<Long> vendorsWithChanges = activeFingerprints.stream()
                .map(fp -> fp.getVendor().getId())
                .collect(Collectors.toSet());

        // Severity breakdown
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        for (ChangeSeverity sev : ChangeSeverity.values()) {
            severityCounts.put(sev.name(), 0);
        }
        for (ChangeFingerprint fp : activeFingerprints) {
            severityCounts.merge(fp.getSeverity().name(), 1, Integer::sum);
        }

        // Most impacted services
        List<DashboardResponse.ImpactedService> impactedServices = buildImpactedServices(activeFingerprints);

        // Recent drift activity (last 10 audit runs)
        List<DashboardResponse.RecentActivity> recentActivity = auditRepo.findTop10ByOrderByExecutedAtDesc()
                .stream()
                .map(run -> DashboardResponse.RecentActivity.builder()
                        .auditRunId(run.getId())
                        .vendorName(run.getVendor().getVendorName())
                        .status(run.getStatus().name())
                        .totalChanges(run.getTotalChanges())
                        .breakingChanges(run.getBreakingChanges())
                        .executedAt(run.getExecutedAt())
                        .build())
                .toList();

        log.info("Dashboard built: {} vendors ({} active), {} active breaking changes across {} vendors",
                totalVendors, activeVendors, activeFingerprints.size(), vendorsWithChanges.size());

        return DashboardResponse.builder()
                .totalVendors((int) totalVendors)
                .activeVendors((int) activeVendors)
                .activeBreakingChanges(activeFingerprints.size())
                .vendorsWithActiveChanges(vendorsWithChanges.size())
                .changesBySeverity(severityCounts)
                .mostImpactedServices(impactedServices)
                .recentDriftActivity(recentActivity)
                .build();
    }

    /**
     * Resolves which internal services are most impacted by active breaking changes,
     * by correlating active fingerprints against the telemetry registry.
     */
    private List<DashboardResponse.ImpactedService> buildImpactedServices(
            List<ChangeFingerprint> activeFingerprints) {

        // serviceName → count of distinct fingerprint endpoints affected
        Map<String, Set<String>> serviceEndpoints = new LinkedHashMap<>();

        for (ChangeFingerprint fp : activeFingerprints) {
            Set<String> consumers = telemetryRegistry.findConsumers(
                    fp.getVendor().getId(),
                    fp.getEndpointPath(),
                    fp.getJsonPointer(),
                    fp.getHttpMethod());

            for (String serviceName : consumers) {
                String endpointKey = fp.getVendor().getId() + ":" + fp.getEndpointPath() + ":" + fp.getJsonPointer();
                serviceEndpoints.computeIfAbsent(serviceName, k -> new HashSet<>()).add(endpointKey);
            }
        }

        return serviceEndpoints.entrySet().stream()
                .map(e -> DashboardResponse.ImpactedService.builder()
                        .serviceName(e.getKey())
                        .affectedEndpoints(e.getValue().size())
                        .build())
                .sorted((a, b) -> Integer.compare(b.getAffectedEndpoints(), a.getAffectedEndpoints()))
                .limit(10)
                .toList();
    }
}
