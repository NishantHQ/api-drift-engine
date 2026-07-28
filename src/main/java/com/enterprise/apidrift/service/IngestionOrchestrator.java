package com.enterprise.apidrift.service;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.engine.*;
import com.enterprise.apidrift.engine.telemetry.UsageCorrelationService;
import com.enterprise.apidrift.entity.*;
import com.enterprise.apidrift.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Central orchestrator that ties together the full pipeline:
 *  Fetch → Hash Check → Parse → Diff → Correlate → Fingerprint → Alert
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionOrchestrator {

    private final EgressFetchService fetchService;
    private final OpenApiNormalizationService normalizationService;
    private final DirectionalCompatibilityEvaluator compatibilityEvaluator;
    private final FingerprintService fingerprintService;
    private final UsageCorrelationService correlationService;
    private final AlertDispatcherService alertDispatcher;
    private final EncryptionService encryptionService;

    private final VendorConfigRepository vendorRepo;
    private final SpecSnapshotRepository snapshotRepo;
    private final DiffAuditRunRepository auditRepo;

    /**
     * Execute the full pipeline for all active vendors.
     */
    public void runForAllActiveVendors() {
        List<VendorConfig> activeVendors = vendorRepo.findByIsActiveTrue();
        log.info("Starting ingestion pipeline for {} active vendors", activeVendors.size());
        for (VendorConfig vendor : activeVendors) {
            try {
                runPipeline(vendor);
            } catch (Exception e) {
                log.error("Pipeline failed for vendor {}: {}", vendor.getVendorName(), e.getMessage(), e);
                createFailedAuditRun(vendor, e.getMessage());
            }
        }
    }

    /**
     * Execute the full pipeline for a single vendor.
     */
    @Transactional
    public DiffAuditRun runPipeline(VendorConfig vendor) {
        log.info("=== Starting pipeline for vendor: {} ===", vendor.getVendorName());

        // Step 1: Fetch remote spec
        String authHeader = buildAuthHeader(vendor);
        String rawSpec = fetchService.fetchSpec(vendor.getSpecUrl(), authHeader);

        // Step 2: Compute SHA-256 content hash
        String contentHash = sha256(rawSpec);

        // Step 3: Check for changes vs. latest snapshot
        SpecSnapshot latestSnapshot = snapshotRepo.findLatestByVendorId(vendor.getId()).orElse(null);
        if (latestSnapshot != null && latestSnapshot.getContentHash().equals(contentHash)) {
            log.info("NO_CHANGE_DETECTED for vendor {} (hash: {})", vendor.getVendorName(), contentHash);
            return DiffAuditRun.builder()
                    .vendor(vendor)
                    .oldSnapshot(latestSnapshot)
                    .newSnapshot(latestSnapshot)
                    .status(RunStatus.NO_CHANGE_DETECTED)
                    .executedAt(OffsetDateTime.now())
                    .build();
        }

        // Step 4: Parse & normalize
        JsonNode normalizedSpec = normalizationService.parseAndNormalize(rawSpec);

        // Step 5: Persist new snapshot
        String specVersion = normalizedSpec.has("openapi")
                ? normalizedSpec.get("openapi").asText()
                : normalizedSpec.has("swagger") ? normalizedSpec.get("swagger").asText() : "unknown";

        SpecSnapshot newSnapshot = SpecSnapshot.builder()
                .vendor(vendor)
                .contentHash(contentHash)
                .specVersion(specVersion)
                .rawSpec(rawSpec)
                .createdAt(OffsetDateTime.now())
                .build();
        newSnapshot = snapshotRepo.save(newSnapshot);

        // Step 6: Execute directional diff
        JsonNode oldNormalized = latestSnapshot != null
                ? normalizationService.parseAndNormalize(latestSnapshot.getRawSpec())
                : null;

        List<DetectedChange> changes = compatibilityEvaluator.evaluate(
                oldNormalized != null ? oldNormalized : normalizedSpec,
                normalizedSpec);

        // Step 7: Create audit run
        DiffAuditRun auditRun = DiffAuditRun.builder()
                .vendor(vendor)
                .oldSnapshot(latestSnapshot)
                .newSnapshot(newSnapshot)
                .totalChanges(changes.size())
                .breakingChanges((int) changes.stream().filter(DetectedChange::isBreaking).count())
                .status(RunStatus.IN_PROGRESS)
                .executedAt(OffsetDateTime.now())
                .build();
        auditRun = auditRepo.save(auditRun);

        // Step 8: Correlate with telemetry (adjusts severity)
        changes = correlationService.correlateWithUsage(vendor.getId(), changes);

        // Step 9: Fingerprint & deduplicate
        List<DetectedChange> alertableChanges = fingerprintService.deduplicateAndFilter(
                vendor.getId(), changes, auditRun);

        // Step 10: Dispatch alerts
        alertDispatcher.dispatchAlerts(vendor, alertableChanges);

        // Step 11: Update audit run status
        auditRun.setTotalChanges(changes.size());
        auditRun.setBreakingChanges((int) changes.stream().filter(DetectedChange::isBreaking).count());
        auditRun.setStatus(RunStatus.SUCCESS);
        auditRun = auditRepo.save(auditRun);

        log.info("=== Pipeline complete for vendor {}: {} changes, {} breaking, {} alerts ===",
                vendor.getVendorName(),
                changes.size(),
                changes.stream().filter(DetectedChange::isBreaking).count(),
                alertableChanges.size());

        return auditRun;
    }

    private String buildAuthHeader(VendorConfig vendor) {
        if (vendor.getAuthHeaderName() == null || vendor.getEncryptedAuthToken() == null) {
            return null;
        }
        String token = encryptionService.decrypt(vendor.getEncryptedAuthToken());
        return vendor.getAuthHeaderName() + " " + token;
    }

    private void createFailedAuditRun(VendorConfig vendor, String errorMessage) {
        try {
            DiffAuditRun run = DiffAuditRun.builder()
                    .vendor(vendor)
                    .status(RunStatus.FAILURE)
                    .totalChanges(0)
                    .breakingChanges(0)
                    .executedAt(OffsetDateTime.now())
                    .build();
            auditRepo.save(run);
        } catch (Exception e) {
            log.error("Failed to persist failure audit run: {}", e.getMessage());
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
