package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.dto.DiffTriggerResponse;
import com.enterprise.apidrift.entity.DiffAuditRun;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.IngestionOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for diff operations and audit history.
 * Endpoint: /api/v1/diffs
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diffs")
@RequiredArgsConstructor
public class DiffController {

    private final IngestionOrchestrator orchestrator;
    private final VendorConfigRepository vendorRepo;
    private final DiffAuditRunRepository auditRepo;
    private final ChangeFingerprintRepository fingerprintRepo;

    /**
     * Manually trigger a diff run for a specific vendor.
     */
    @PostMapping("/trigger/{vendorId}")
    public ResponseEntity<DiffTriggerResponse> triggerDiff(@PathVariable Long vendorId) {
        VendorConfig vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Manual diff triggered for vendor: {}", vendor.getVendorName());
        DiffAuditRun auditRun = orchestrator.runPipeline(vendor);

        List<DetectedChange> changes = fingerprintRepo.findByAuditRunId(auditRun.getId())
                .stream()
                .map(fp -> DetectedChange.builder()
                        .changeType(fp.getChangeType())
                        .severity(fp.getSeverity())
                        .httpMethod(fp.getHttpMethod())
                        .endpointPath(fp.getEndpointPath())
                        .jsonPointer(fp.getJsonPointer())
                        .description(fp.getDescription())
                        .fingerprintHash(fp.getFingerprintHash())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(DiffTriggerResponse.builder()
                .auditRunId(auditRun.getId())
                .vendorName(vendor.getVendorName())
                .status(auditRun.getStatus().name())
                .totalChanges(auditRun.getTotalChanges())
                .breakingChanges(auditRun.getBreakingChanges())
                .executedAt(auditRun.getExecutedAt())
                .changes(changes)
                .build());
    }

    /**
     * Get audit run history for a vendor.
     */
    @GetMapping("/history/{vendorId}")
    public ResponseEntity<List<DiffTriggerResponse>> getHistory(@PathVariable Long vendorId) {
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }

        List<DiffTriggerResponse> history = auditRepo.findByVendorIdOrderByExecutedAtDesc(vendorId)
                .stream()
                .map(run -> DiffTriggerResponse.builder()
                        .auditRunId(run.getId())
                        .vendorName(run.getVendor().getVendorName())
                        .status(run.getStatus().name())
                        .totalChanges(run.getTotalChanges())
                        .breakingChanges(run.getBreakingChanges())
                        .executedAt(run.getExecutedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(history);
    }

    /**
     * Get active (unresolved) changes for a vendor.
     */
    @GetMapping("/active/{vendorId}")
    public ResponseEntity<List<DetectedChange>> getActiveChanges(@PathVariable Long vendorId) {
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }

        List<DetectedChange> active = fingerprintRepo.findByVendorIdAndIsActiveTrue(vendorId)
                .stream()
                .map(fp -> DetectedChange.builder()
                        .changeType(fp.getChangeType())
                        .severity(fp.getSeverity())
                        .direction(fp.getChangeType().contains("REQUEST") ? "REQUEST"
                                : fp.getChangeType().contains("RESPONSE") ? "RESPONSE" : "WEBHOOK")
                        .httpMethod(fp.getHttpMethod())
                        .endpointPath(fp.getEndpointPath())
                        .jsonPointer(fp.getJsonPointer())
                        .description(fp.getDescription())
                        .fingerprintHash(fp.getFingerprintHash())
                        .isBreaking(true)
                        .build())
                .toList();

        return ResponseEntity.ok(active);
    }
}
