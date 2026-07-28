package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.VendorConfigRequest;
import com.enterprise.apidrift.dto.VendorConfigResponse;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.EncryptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * REST controller for managing vendor configurations.
 * Endpoint: /api/v1/vendors
 */
@RestController
@RequestMapping("/api/v1/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final VendorConfigRepository vendorRepo;
    private final EncryptionService encryptionService;

    @GetMapping
    public List<VendorConfigResponse> listAll() {
        return vendorRepo.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<VendorConfigResponse> getById(@PathVariable Long id) {
        return vendorRepo.findById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<VendorConfigResponse> create(@Valid @RequestBody VendorConfigRequest request) {
        if (vendorRepo.existsByVendorName(request.getVendorName())) {
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
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        vendor = vendorRepo.save(vendor);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(vendor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<VendorConfigResponse> update(@PathVariable Long id,
                                                        @Valid @RequestBody VendorConfigRequest request) {
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
                    vendor = vendorRepo.save(vendor);
                    return ResponseEntity.ok(toResponse(vendor));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!vendorRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        vendorRepo.deleteById(id);
        return ResponseEntity.noContent().build();
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
                .createdAt(vendor.getCreatedAt())
                .updatedAt(vendor.getUpdatedAt())
                .build();
    }
}
