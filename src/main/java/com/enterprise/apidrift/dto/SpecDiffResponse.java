package com.enterprise.apidrift.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SpecDiffResponse {
    private SpecSnapshotResponse oldSnapshot;
    private SpecSnapshotResponse newSnapshot;
    private List<DetectedChange> changes;
}
