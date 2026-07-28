package com.enterprise.apidrift.dto;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class DiffTriggerResponse {
    private Long auditRunId;
    private String vendorName;
    private String status;
    private int totalChanges;
    private int breakingChanges;
    private OffsetDateTime executedAt;
    private List<DetectedChange> changes;
}
