package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.VendorConfigRequest;
import com.enterprise.apidrift.dto.VendorConfigResponse;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.entity.VendorHealthStatus;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.EncryptionService;
import com.enterprise.apidrift.service.VendorHealthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final VendorConfigRepository vendorRepo;
    private final EncryptionService encryptionService;
    private final VendorHealthService healthService;

    @GetMapping
    public List<VendorConfigResponse> listAll(@RequestParam(required = false) String tag) {
        if (tag != null && !tag.isBlank()) {
            log.info("GET /api/v1/vendors — filtered by tag '{}'", tag);
            return vendorRepo.findByTag(tag).stream()
                    .map(this::toResponse)
                    .toList();
        }
        log.info("GET /api/v1/vendors — listing all vendors");
        return vendorRepo.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorConfigResponse> getById(@PathVariable Long id) {
        log.info("GET /api/v1/vendors/{}", id);
        return vendorRepo.findById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<VendorConfigResponse> create(@Valid @RequestBody VendorConfigRequest request) {
        log.info("POST /api/v1/vendors — creating vendor '{}'", request.getVendorName());
        if (vendorRepo.existsByVendorName(request.getVendorName())) {
            log.warn("Vendor '{}' already exists — conflict", request.getVendorName());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        VendorConfig vendor = VendorConfig.builder()
                .vendorName(request.getVendorName())
                .specUrl(request.getSpecUrl())
                .cronExpression(request.getCronExpression())
                .authHeaderName(request.getAuthHeaderName())
                .encryptedAuthToken(request.getAuthToken() != null
                        ? encryptionService.encrypt(request.getAuthToken())
                        : null)
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .tags(request.getTags() != null ? request.getTags() : Collections.emptyList())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        vendor = vendorRepo.save(vendor);
        log.info("Vendor '{}' created with id={}", vendor.getVendorName(), vendor.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(vendor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VendorConfigResponse> update(@PathVariable Long id,
                                                        @Valid @RequestBody VendorConfigRequest request) {
        log.info("PUT /api/v1/vendors/{} — updating to '{}'", id, request.getVendorName());
        return vendorRepo.findById(id)
                .map(vendor -> {
                    vendor.setVendorName(request.getVendorName());
                    vendor.setSpecUrl(request.getSpecUrl());
                    vendor.setCronExpression(request.getCronExpression());
                    vendor.setAuthHeaderName(request.getAuthHeaderName());
                    if (request.getAuthToken() != null && !request.getAuthToken().isBlank()) {
                        vendor.setEncryptedAuthToken(encryptionService.encrypt(request.getAuthToken()));
                    }
                    vendor.setIsActive(request.getIsActive());
                    if (request.getTags() != null) {
                        vendor.setTags(request.getTags());
                    }
                    vendor = vendorRepo.save(vendor);
                    log.info("Vendor id={} updated", id);
                    return ResponseEntity.ok(toResponse(vendor));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/vendors/{}", id);
        if (!vendorRepo.existsById(id)) {
            log.warn("Vendor id={} not found for delete", id);
            return ResponseEntity.notFound().build();
        }
        vendorRepo.deleteById(id);
        log.info("Vendor id={} deleted", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get the circuit-breaker health status for a vendor's spec fetching.
     */
    @GetMapping("/{id}/health")
    public ResponseEntity<Map<String, String>> getHealth(@PathVariable Long id) {
        log.info("GET /api/v1/vendors/{}/health", id);
        if (!vendorRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        VendorHealthStatus status = healthService.getStatus(id);
        return ResponseEntity.ok(Map.of(
                "vendorId", id.toString(),
                "healthStatus", status.name()));
    }

    /**
     * Reset the circuit breaker for a vendor (manual intervention after fixing the issue).
     */
    @PostMapping("/{id}/health/reset")
    public ResponseEntity<Map<String, String>> resetHealth(@PathVariable Long id) {
        log.info("POST /api/v1/vendors/{}/health/reset", id);
        if (!vendorRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        healthService.reset(id);
        return ResponseEntity.ok(Map.of(
                "vendorId", id.toString(),
                "healthStatus", VendorHealthStatus.HEALTHY.name(),
                "message", "Circuit breaker reset — vendor is now HEALTHY"));
    }

    private VendorConfigResponse toResponse(VendorConfig vendor) {
        return VendorConfigResponse.builder()
                .id(vendor.getId())
                .vendorName(vendor.getVendorName())
                .specUrl(vendor.getSpecUrl())
                .cronExpression(vendor.getCronExpression())
                .authHeaderName(vendor.getAuthHeaderName())
                .authTokenConfigured(vendor.getEncryptedAuthToken() != null)
                .isActive(vendor.getIsActive())
                .tags(vendor.getTags())
                .healthStatus(healthService.getStatus(vendor.getId()).name())
                .createdAt(vendor.getCreatedAt())
                .updatedAt(vendor.getUpdatedAt())
                .build();
    }
}
