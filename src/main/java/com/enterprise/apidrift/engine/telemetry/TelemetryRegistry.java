package com.enterprise.apidrift.engine.telemetry;

import com.enterprise.apidrift.entity.ServiceDependency;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ServiceDependencyRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Telemetry registry that resolves which internal services consume which
 * vendor API endpoints/fields.
 *
 * Reads from the service_dependencies table (populated via the
 * /api/v1/telemetry/register API), falling back to mock data only when
 * the database is empty (e.g., development with no registrations yet).
 */
@Slf4j
@Component
public class TelemetryRegistry {

    private final ServiceDependencyRepository dependencyRepository;
    private final VendorConfigRepository vendorConfigRepository;

    public TelemetryRegistry(ServiceDependencyRepository dependencyRepository,
                             VendorConfigRepository vendorConfigRepository) {
        this.dependencyRepository = dependencyRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        seedMockDataIfEmpty();
    }

    /**
     * Find all internal services that consume a specific vendor endpoint + field.
     */
    public Set<String> findConsumers(Long vendorId, String endpointPath,
                                     String jsonPointer, String httpMethod) {
        Set<String> consumers = dependencyRepository.findConsumers(
                vendorId, endpointPath != null ? endpointPath : "",
                jsonPointer != null ? jsonPointer : "");

        if (!consumers.isEmpty()) {
            log.debug("Telemetry hit: vendor={}, endpoint={}, field={} → {}",
                    vendorId, endpointPath, jsonPointer, consumers);
        }
        return consumers;
    }

    /**
     * Register a consumer mapping programmatically (also persists to DB).
     */
    public void register(Long vendorId, String endpoint, String jsonPointer, String serviceName) {
        if (dependencyRepository.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                vendorId, serviceName, endpoint, null, jsonPointer)) {
            log.debug("Dependency already exists: vendor={}, endpoint={}, service={} — skipping",
                    vendorId, endpoint, serviceName);
            return;
        }

        ServiceDependency dep = ServiceDependency.builder()
                .vendor(VendorConfig.builder().id(vendorId).build())
                .endpointPath(endpoint)
                .jsonPointer(jsonPointer)
                .serviceName(serviceName)
                .build();
        dependencyRepository.save(dep);
        log.debug("Registered: vendor={}, endpoint={}, field={} → {}", vendorId, endpoint, jsonPointer, serviceName);
    }

    /**
     * Seeds mock data only when the database has no registrations yet.
     * This preserves dev convenience while allowing real data to take precedence.
     */
    private void seedMockDataIfEmpty() {
        if (dependencyRepository.count() > 0) {
            log.info("Telemetry registry has {} DB entries — skipping mock seed", dependencyRepository.count());
            return;
        }

        // Only seed if the referenced vendors actually exist
        if (!vendorConfigRepository.existsById(1L) && !vendorConfigRepository.existsById(2L)) {
            log.info("No seed vendors found — skipping telemetry mock data seed");
            return;
        }

        log.info("Seeding telemetry registry with mock data for development");
        if (vendorConfigRepository.existsById(1L)) {
            register(1L, "/v1/charges", "/requestBody/properties/amount", "payment-service");
            register(1L, "/v1/charges", "/requestBody/properties/currency", "payment-service");
            register(1L, "/v1/charges", "/responses/200/properties/id", "order-service");
            register(1L, "/v1/customers", "/responses/200/properties/email", "user-service");
        }
        if (vendorConfigRepository.existsById(2L)) {
            register(2L, "/admin/api/orders", "/responses/200/properties/line_items", "fulfillment-service");
            register(2L, "/admin/api/products", "/responses/200/properties/variants", "catalog-service");
        }
    }
}
