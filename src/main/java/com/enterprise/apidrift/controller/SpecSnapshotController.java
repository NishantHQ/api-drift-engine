package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.SpecSnapshotResponse;
import com.enterprise.apidrift.entity.SpecSnapshot;
import com.enterprise.apidrift.repository.SpecSnapshotRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * List all snapshots for a vendor (metadata only — no raw spec payload).
     */
    @GetMapping("/{vendorId}")
    public ResponseEntity<List<SpecSnapshotResponse>> listByVendor(@PathVariable Long vendorId) {
        log.info("GET /api/v1/snapshots/{}", vendorId);
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }
        List<SpecSnapshotResponse> snapshots = snapshotRepo
                .findByVendorIdOrderByCreatedAtDesc(vendorId)
                .stream()
                .map(s -> toResponse(s, false))
                .toList();
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
