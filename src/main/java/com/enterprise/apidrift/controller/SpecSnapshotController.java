package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.dto.SpecDiffResponse;
import com.enterprise.apidrift.dto.SpecSnapshotResponse;
import com.enterprise.apidrift.engine.DirectionalCompatibilityEvaluator;
import com.enterprise.apidrift.engine.OpenApiNormalizationService;
import com.enterprise.apidrift.entity.SpecSnapshot;
import com.enterprise.apidrift.repository.SpecSnapshotRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for browsing stored OpenAPI spec snapshots.
 * Endpoint: /api/v1/snapshots
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/snapshots")
@RequiredArgsConstructor
public class SpecSnapshotController {

    private final SpecSnapshotRepository snapshotRepo;
    private final VendorConfigRepository vendorRepo;
    private final OpenApiNormalizationService normalizationService;
    private final DirectionalCompatibilityEvaluator compatibilityEvaluator;

    /**
     * List all snapshots for a vendor (metadata only — no raw spec payload).
     */
    @GetMapping("/{vendorId}")
    public ResponseEntity<Page<SpecSnapshotResponse>> listByVendor(
            @PathVariable Long vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/v1/snapshots/{} — page={}, size={}", vendorId, page, size);
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<SpecSnapshotResponse> snapshots = snapshotRepo
                .findByVendorIdOrderByCreatedAtDesc(vendorId, pageable)
                .map(s -> toResponse(s, false));
        return ResponseEntity.ok(snapshots);
    }

    /**
     * Get the latest snapshot for a vendor, including the full raw spec.
     */
    @GetMapping("/{vendorId}/latest")
    public ResponseEntity<SpecSnapshotResponse> getLatest(@PathVariable Long vendorId) {
        log.info("GET /api/v1/snapshots/{}/latest", vendorId);
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }
        return snapshotRepo.findLatestByVendorId(vendorId)
                .map(s -> ResponseEntity.ok(toResponse(s, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get a specific snapshot by ID, including the full raw spec.
     */
    @GetMapping("/{vendorId}/{snapshotId}")
    public ResponseEntity<SpecSnapshotResponse> getById(@PathVariable Long vendorId,
                                                        @PathVariable Long snapshotId) {
        log.info("GET /api/v1/snapshots/{}/{}", vendorId, snapshotId);
        return snapshotRepo.findById(snapshotId)
                .filter(s -> s.getVendor().getId().equals(vendorId))
                .map(s -> ResponseEntity.ok(toResponse(s, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Compare two snapshots and return both specs with detected changes.
     * Uses the same diff engine as the ingestion pipeline for consistency.
     */
    @GetMapping("/{vendorId}/diff")
    public ResponseEntity<SpecDiffResponse> compareSnapshots(
            @PathVariable Long vendorId,
            @RequestParam Long old,
            @RequestParam Long newSnapshot) {
        log.info("GET /api/v1/snapshots/{}/diff — old={}, new={}", vendorId, old, newSnapshot);

        SpecSnapshot oldSnap = snapshotRepo.findById(old).orElse(null);
        SpecSnapshot newSnap = snapshotRepo.findById(newSnapshot).orElse(null);

        if (oldSnap == null || newSnap == null) {
            return ResponseEntity.notFound().build();
        }
        if (!oldSnap.getVendor().getId().equals(vendorId)
                || !newSnap.getVendor().getId().equals(vendorId)) {
            return ResponseEntity.notFound().build();
        }

        // Parse both specs and run the same diff engine used in ingestion
        JsonNode oldSpec = normalizationService.parseAndNormalize(oldSnap.getRawSpec());
        JsonNode newSpec = normalizationService.parseAndNormalize(newSnap.getRawSpec());
        List<DetectedChange> changes = compatibilityEvaluator.evaluate(oldSpec, newSpec);

        return ResponseEntity.ok(SpecDiffResponse.builder()
                .oldSnapshot(toResponse(oldSnap, true))
                .newSnapshot(toResponse(newSnap, true))
                .changes(changes)
                .build());
    }

    private SpecSnapshotResponse toResponse(SpecSnapshot snapshot, boolean includeRawSpec) {
        return SpecSnapshotResponse.builder()
                .id(snapshot.getId())
                .vendorId(snapshot.getVendor().getId())
                .vendorName(snapshot.getVendor().getVendorName())
                .contentHash(snapshot.getContentHash())
                .specVersion(snapshot.getSpecVersion())
                .createdAt(snapshot.getCreatedAt())
                .rawSpec(includeRawSpec ? snapshot.getRawSpec() : null)
                .build();
    }
}
