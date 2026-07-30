package com.enterprise.apidrift.dto;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class ChangeFeedResponse {
    private Long id;
    private Long vendorId;
    private String vendorName;
    private String changeType;
    private String severity;
    private String httpMethod;
    private String endpointPath;
    private String jsonPointer;
    private String description;
    private String fingerprintHash;
    private boolean isActive;
    private OffsetDateTime firstSeenAt;
    private OffsetDateTime lastSeenAt;
}
