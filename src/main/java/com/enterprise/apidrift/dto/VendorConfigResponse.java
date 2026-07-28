package com.enterprise.apidrift.dto;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class VendorConfigResponse {
    private Long id;
    private String vendorName;
    private String specUrl;
    private String cronExpression;
    private String authHeaderName;
    private boolean authTokenConfigured;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
