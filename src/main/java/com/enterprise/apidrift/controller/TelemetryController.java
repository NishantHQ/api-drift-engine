package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.ServiceDependencyRequest;
import com.enterprise.apidrift.dto.ServiceDependencyResponse;
import com.enterprise.apidrift.entity.ServiceDependency;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ServiceDependencyRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.engine.telemetry.TelemetryRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * REST controller for telemetry/dependency registration.
 * Internal services use these endpoints to self-register their
 * vendor API dependencies, enabling real severity correlation.
 *
 * Endpoint: /api/v1/telemetry
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final ServiceDependencyRepository dependencyRepo;
    private final VendorConfigRepository vendorRepo;
    private final TelemetryRegistry telemetryRegistry;

    /**
     * Register a service dependency on a vendor API.
     * Idempotent — duplicate registrations return 200 instead of 201.
     */
    @PostMapping("/register")
    public ResponseEntity<ServiceDependencyResponse> register(@Valid @RequestBody ServiceDependencyRequest request) {
        log.info("POST /api/v1/telemetry/register — service={}, vendor={}, endpoint={} {}",
                request.getServiceName(), request.getVendorId(),
                request.getHttpMethod(), request.getEndpointPath());

        VendorConfig vendor = vendorRepo.findById(request.getVendorId()).orElse(null);
        if (vendor == null) {
            log.warn("Registration failed: vendor id={} not found", request.getVendorId());
            return ResponseEntity.notFound().build();
        }

        // Idempotency check
        boolean exists = dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                request.getVendorId(), request.getServiceName(),
                request.getEndpointPath(), request.getHttpMethod(), request.getJsonPointer());

        if (exists) {
            log.debug("Dependency already registered: service={}, vendor={}, endpoint={}",
                    request.getServiceName(), request.getVendorId(), request.getEndpointPath());
            // Find and return the existing one
            List<ServiceDependency> existing = dependencyRepo.findByVendorIdAndServiceName(
                    request.getVendorId(), request.getServiceName());
            var match = existing.stream()
                    .filter(d -> matches(d, request))
                    .findFirst();
            if (match.isPresent()) {
                return ResponseEntity.ok(toResponse(match.get()));
            }
        }

        ServiceDependency dependency = ServiceDependency.builder()
                .vendor(vendor)
                .endpointPath(request.getEndpointPath())
                .httpMethod(request.getHttpMethod())
                .jsonPointer(request.getJsonPointer())
                .serviceName(request.getServiceName())
                .build();

        dependency = dependencyRepo.save(dependency);
        log.info("Service dependency registered: id={}, service={} → vendor={}",
                dependency.getId(), dependency.getServiceName(), vendor.getVendorName());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(dependency));
    }

    /**
     * List registered dependencies, optionally filtered by vendor and/or service.
     */
    @GetMapping("/dependencies")
    public List<ServiceDependencyResponse> listDependencies(
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String serviceName) {
        log.info("GET /api/v1/telemetry/dependencies — vendorId={}, serviceName={}", vendorId, serviceName);

        List<ServiceDependency> dependencies;
        if (vendorId != null && serviceName != null) {
            dependencies = dependencyRepo.findByVendorIdAndServiceName(vendorId, serviceName);
        } else if (vendorId != null) {
            dependencies = dependencyRepo.findByVendorId(vendorId);
        } else if (serviceName != null) {
            dependencies = dependencyRepo.findByServiceName(serviceName);
        } else {
            dependencies = dependencyRepo.findAll();
        }

        return dependencies.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Get all unique service names that depend on a given vendor.
     */
    @GetMapping("/services/{vendorId}")
    public Set<String> getConsumingServices(@PathVariable Long vendorId) {
        log.info("GET /api/v1/telemetry/services/{}", vendorId);
        return dependencyRepo.findDistinctServiceNamesByVendorId(vendorId);
    }

    /**
     * Remove a specific dependency registration.
     */
    @DeleteMapping("/dependencies/{id}")
    public ResponseEntity<Void> deleteDependency(@PathVariable Long id) {
        log.info("DELETE /api/v1/telemetry/dependencies/{}", id);
        if (!dependencyRepo.existsById(id)) {
            log.warn("Dependency id={} not found for delete", id);
            return ResponseEntity.notFound().build();
        }
        dependencyRepo.deleteById(id);
        log.info("Dependency id={} deleted", id);
        return ResponseEntity.noContent().build();
    }

    private ServiceDependencyResponse toResponse(ServiceDependency dep) {
        return ServiceDependencyResponse.builder()
                .id(dep.getId())
                .vendorId(dep.getVendor().getId())
                .vendorName(dep.getVendor().getVendorName())
                .endpointPath(dep.getEndpointPath())
                .httpMethod(dep.getHttpMethod())
                .jsonPointer(dep.getJsonPointer())
                .serviceName(dep.getServiceName())
                .createdAt(dep.getCreatedAt())
                .build();
    }

    private boolean matches(ServiceDependency dep, ServiceDependencyRequest request) {
        return nullSafeEquals(dep.getEndpointPath(), request.getEndpointPath())
                && nullSafeEquals(dep.getHttpMethod(), request.getHttpMethod())
                && nullSafeEquals(dep.getJsonPointer(), request.getJsonPointer());
    }

    private boolean nullSafeEquals(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }
}
