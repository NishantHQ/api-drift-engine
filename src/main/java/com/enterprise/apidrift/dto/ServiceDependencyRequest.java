package com.enterprise.apidrift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ServiceDependencyRequest {

    @NotNull
    private Long vendorId;

    private String endpointPath;

    private String httpMethod;

    private String jsonPointer;

    @NotBlank
    private String serviceName;
}
