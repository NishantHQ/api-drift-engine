package com.enterprise.apidrift.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class VendorConfigRequest {

    @NotBlank @Size(max = 100)
    private String vendorName;

    @NotBlank @Size(max = 2048)
    private String specUrl;

    private String cronExpression = "0 0 * * * *";

    @Size(max = 100)
    private String authHeaderName;

    private String authToken;       // Plaintext — encrypted before storage

    private Boolean isActive = true;

    private List<String> tags;
}
