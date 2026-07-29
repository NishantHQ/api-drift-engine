package com.enterprise.apidrift.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {

    private int totalVendors;
    private int activeVendors;
    private int activeBreakingChanges;
    private int vendorsWithActiveChanges;

    /** Severity → count of active breaking changes */
    private Map<String, Integer> changesBySeverity;

    /** Top internal services affected by active breaking changes */
    private List<ImpactedService> mostImpactedServices;

    /** Last 10 audit runs across all vendors */
    private List<RecentActivity> recentDriftActivity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedService {
        private String serviceName;
        private int affectedEndpoints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentActivity {
        private Long auditRunId;
        private String vendorName;
        private String status;
        private int totalChanges;
        private int breakingChanges;
        private OffsetDateTime executedAt;
    }
}
