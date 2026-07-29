package com.enterprise.apidrift.engine.telemetry;

import com.enterprise.apidrift.entity.ServiceDependency;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ServiceDependencyRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryRegistryTest {

    @Mock private ServiceDependencyRepository dependencyRepo;
    @Mock private VendorConfigRepository vendorConfigRepo;

    @InjectMocks
    private TelemetryRegistry registry;

    @BeforeEach
    void setUp() {
        // No mock data seeding since seed-mock-data defaults to false
    }

    @Test
    @DisplayName("findConsumers delegates to repository")
    void findConsumersDelegatesToRepo() {
        when(dependencyRepo.findConsumers(eq(1L), anyString(), anyString()))
                .thenReturn(Set.of("payment-service", "order-service"));

        Set<String> consumers = registry.findConsumers(1L, "/v1/charges", "/requestBody/properties/amount", "POST");

        assertThat(consumers).containsExactlyInAnyOrder("payment-service", "order-service");
    }

    @Test
    @DisplayName("findConsumers with null path returns empty set")
    void findConsumersNullPath() {
        when(dependencyRepo.findConsumers(eq(1L), eq(""), eq("")))
                .thenReturn(Set.of());

        Set<String> consumers = registry.findConsumers(1L, null, null, "GET");

        assertThat(consumers).isEmpty();
    }

    @Test
    @DisplayName("register persists new dependency when not duplicate")
    void registerPersistsNewDependency() {
        when(dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                1L, "payment-service", "/v1/charges", null, "/requestBody/properties/amount"))
                .thenReturn(false);
        when(vendorConfigRepo.getReferenceById(1L))
                .thenReturn(VendorConfig.builder().id(1L).build());
        when(dependencyRepo.save(any(ServiceDependency.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        registry.register(1L, "/v1/charges", "/requestBody/properties/amount", "payment-service");

        verify(dependencyRepo).save(any(ServiceDependency.class));
    }

    @Test
    @DisplayName("register skips duplicate dependency")
    void registerSkipsDuplicate() {
        when(dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                1L, "payment-service", "/v1/charges", null, "/requestBody/properties/amount"))
                .thenReturn(true);

        registry.register(1L, "/v1/charges", "/requestBody/properties/amount", "payment-service");

        verify(dependencyRepo, never()).save(any());
    }

    @Test
    @DisplayName("register with null jsonPointer works")
    void registerWithNullPointer() {
        when(dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                1L, "test-svc", "/v1/test", null, null))
                .thenReturn(false);
        when(vendorConfigRepo.getReferenceById(1L))
                .thenReturn(VendorConfig.builder().id(1L).build());
        when(dependencyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        registry.register(1L, "/v1/test", null, "test-svc");

        verify(dependencyRepo).save(any(ServiceDependency.class));
    }
}
