package com.enterprise.apidrift.dto;

import com.enterprise.apidrift.entity.ChangeSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectedChange {

    private String changeType;
    private ChangeSeverity severity;
    private String direction;       // REQUEST, RESPONSE, WEBHOOK
    private String httpMethod;
    private String endpointPath;
    private String jsonPointer;
    private String description;
    private String fingerprintHash;
    private boolean isBreaking;

    /** Internal service name that consumes this endpoint/field, if known. */
    private String consumingService;
}
