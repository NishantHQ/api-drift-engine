package com.enterprise.apidrift.engine.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Mock telemetry registry mapping (VendorID + Endpoint + JSON Pointer → Internal Services).
 *
 * In production: queries gateway logs, distributed tracing (e.g. Tempo/Zipkin),
 * or a static dependency mapping database.
 */
@Slf4j
@Component
public class TelemetryRegistry {

    /**
     * Mock registry: VendorID → (Endpoint → (JSONPointer → [service names])).
     */
    private final Map<Long, Map<String, Map<String, Set<String>>>> registry = new HashMap<>();

    public TelemetryRegistry() {
        // Seed with mock data for development
        seedMockData();
    }

    /**
     * Find all internal services that consume a specific vendor endpoint + field.
     */
    public Set<String> findConsumers(Long vendorId, String endpointPath,
                                      String jsonPointer, String httpMethod) {
        Map<String, Map<String, Set<String>>> vendorMap = registry.get(vendorId);
        if (vendorMap == null) return Collections.emptySet();

        // Try exact endpoint match first, then prefix
        Set<String> consumers = new HashSet<>();
        for (var entry : vendorMap.entrySet()) {
            if (endpointPath != null && endpointPath.contains(entry.getKey())) {
                for (var fieldEntry : entry.getValue().entrySet()) {
                    if (jsonPointer == null || jsonPointer.contains(fieldEntry.getKey())) {
                        consumers.addAll(fieldEntry.getValue());
                    }
                }
            }
        }

        if (!consumers.isEmpty()) {
            log.debug("Telemetry hit: vendor={}, endpoint={}, field={} → {}",
                    vendorId, endpointPath, jsonPointer, consumers);
        }
        return consumers;
    }

    /**
     * Register a consumer mapping programmatically.
     */
    public void register(Long vendorId, String endpoint, String jsonPointer, String serviceName) {
        registry.computeIfAbsent(vendorId, k -> new HashMap<>())
                .computeIfAbsent(endpoint, k -> new HashMap<>())
                .computeIfAbsent(jsonPointer, k -> new HashSet<>())
                .add(serviceName);
    }

    private void seedMockData() {
        // Vendor 1: Stripe-like payment gateway
        register(1L, "/v1/charges", "/requestBody/properties/amount", "payment-service");
        register(1L, "/v1/charges", "/requestBody/properties/currency", "payment-service");
        register(1L, "/v1/charges", "/responses/200/properties/id", "order-service");
        register(1L, "/v1/customers", "/responses/200/properties/email", "user-service");

        // Vendor 2: Shopify-like e-commerce
        register(2L, "/admin/api/orders", "/responses/200/properties/line_items", "fulfillment-service");
        register(2L, "/admin/api/products", "/responses/200/properties/variants", "catalog-service");

        log.info("Telemetry registry seeded with {} vendors", registry.size());
    }
}
