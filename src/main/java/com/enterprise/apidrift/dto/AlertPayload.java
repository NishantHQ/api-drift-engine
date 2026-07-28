package com.enterprise.apidrift.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertPayload {

    private String vendorName;
    private Long vendorId;
    private String changeType;
    private String severity;
    private String direction;
    private String httpMethod;
    private String endpointPath;
    private String jsonPointer;
    private String description;
    private String consumingService;
    private String fingerprintHash;
    private boolean isBreaking;
}
