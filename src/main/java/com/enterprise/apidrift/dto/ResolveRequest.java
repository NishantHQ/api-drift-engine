package com.enterprise.apidrift.dto;

import lombok.Data;

@Data
public class ResolveRequest {

    private String resolvedBy;
    private String resolutionNotes;
}
