package com.enterprise.apidrift.dto;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class SpecSnapshotResponse {
    private Long id;
    private Long vendorId;
    private String vendorName;
    private String contentHash;
    private String specVersion;
    private OffsetDateTime createdAt;

    // Only populated for single-snapshot lookups (latest / by-id).
    // Null in list responses to keep payloads small.
    private String rawSpec;
}
