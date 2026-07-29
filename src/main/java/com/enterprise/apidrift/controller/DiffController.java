package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.dto.DiffTriggerResponse;
import com.enterprise.apidrift.dto.ResolveRequest;
import com.enterprise.apidrift.entity.ChangeFingerprint;
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

import java.time.OffsetDateTime;
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
        log.info("POST /api/v1/diffs/trigger/{} — manual diff triggered", vendorId);
        VendorConfig vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null) {
            log.warn("Trigger failed: vendor id={} not found", vendorId);
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
        log.info("GET /api/v1/diffs/history/{}", vendorId);
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
        log.info("GET /api/v1/diffs/active/{}", vendorId);
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

    /**
     * Manually resolve a change fingerprint.
     * Teams use this to acknowledge they've handled a breaking change.
     * The fingerprint is marked inactive and will not re-alert unless
     * the change reappears in a future diff run.
     */
    @PostMapping("/resolve/{fingerprintId}")
    public ResponseEntity<DetectedChange> resolveChange(@PathVariable Long fingerprintId,
                                                         @RequestBody(required = false) ResolveRequest request) {
        log.info("POST /api/v1/diffs/resolve/{} — manual resolution", fingerprintId);

        ChangeFingerprint fingerprint = fingerprintRepo.findById(fingerprintId).orElse(null);
        if (fingerprint == null) {
            log.warn("Resolution failed: fingerprint id={} not found", fingerprintId);
            return ResponseEntity.notFound().build();
        }

        fingerprint.setIsActive(false);
        fingerprint.setResolvedBy(request != null ? request.getResolvedBy() : null);
        fingerprint.setResolutionNotes(request != null ? request.getResolutionNotes() : null);
        fingerprint.setResolvedAt(OffsetDateTime.now());
        fingerprintRepo.save(fingerprint);

        log.info("Fingerprint {} resolved by {} — {}",
                fingerprint.getFingerprintHash(),
                fingerprint.getResolvedBy() != null ? fingerprint.getResolvedBy() : "unknown",
                fingerprint.getResolutionNotes() != null ? fingerprint.getResolutionNotes() : "no notes");

        return ResponseEntity.ok(DetectedChange.builder()
                .changeType(fingerprint.getChangeType())
                .severity(fingerprint.getSeverity())
                .httpMethod(fingerprint.getHttpMethod())
                .endpointPath(fingerprint.getEndpointPath())
                .jsonPointer(fingerprint.getJsonPointer())
                .description(fingerprint.getDescription())
                .fingerprintHash(fingerprint.getFingerprintHash())
                .isBreaking(true)
                .build());
    }
}
