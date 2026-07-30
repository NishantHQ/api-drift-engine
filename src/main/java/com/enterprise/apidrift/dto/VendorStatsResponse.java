package com.enterprise.apidrift.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorStatsResponse {
    private Long vendorId;
    private String vendorName;
    private long totalAuditRuns;
    private long totalChangesDetected;
    private long totalBreakingChanges;
    private long activeChanges;
    private List<MonthlyStats> monthlyBreakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyStats {
        private String yearMonth;
        private long auditRuns;
        private long changesDetected;
        private long breakingChanges;
        private long resolved;
        private long unresolved;
    }
}
