package com.enterprise.apidrift.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDependencyResponse {

    private Long id;
    private Long vendorId;
    private String vendorName;
    private String endpointPath;
    private String httpMethod;
    private String jsonPointer;
    private String serviceName;
    private OffsetDateTime createdAt;
}
