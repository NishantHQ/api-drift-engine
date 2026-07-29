package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.ServiceDependencyRequest;
import com.enterprise.apidrift.entity.ServiceDependency;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.engine.telemetry.TelemetryRegistry;
import com.enterprise.apidrift.repository.ServiceDependencyRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryControllerTest {

    @Mock private ServiceDependencyRepository dependencyRepo;
    @Mock private VendorConfigRepository vendorRepo;
    @Mock private TelemetryRegistry telemetryRegistry;

    @InjectMocks
    private TelemetryController controller;

    private ServiceDependency dep(Long id, Long vendorId, String service) {
        return ServiceDependency.builder().id(id)
                .vendor(VendorConfig.builder().id(vendorId).vendorName("V" + vendorId).build())
                .endpointPath("/api/test").httpMethod("GET").jsonPointer("/x")
                .serviceName(service).createdAt(OffsetDateTime.now()).build();
    }

    @Test @DisplayName("POST register returns 404 for missing vendor")
    void registerVendorNotFound() {
        var req = new ServiceDependencyRequest();
        req.setVendorId(99L); req.setServiceName("test-svc");
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());

        assertThat(controller.register(req).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("POST register creates dependency and returns 201")
    void registerSuccess() {
        var req = new ServiceDependencyRequest();
        req.setVendorId(1L); req.setServiceName("payment-svc");
        req.setEndpointPath("/api/pay"); req.setHttpMethod("POST"); req.setJsonPointer("/amount");

        when(vendorRepo.findById(1L)).thenReturn(Optional.of(VendorConfig.builder().id(1L).vendorName("Stripe").build()));
        when(dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                1L, "payment-svc", "/api/pay", "POST", "/amount")).thenReturn(false);
        when(dependencyRepo.save(any())).thenReturn(dep(1L, 1L, "payment-svc"));

        var r = controller.register(req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().getServiceName()).isEqualTo("payment-svc");
    }

    @Test @DisplayName("POST register returns 200 for duplicate registration")
    void registerDuplicate() {
        var req = new ServiceDependencyRequest();
        req.setVendorId(1L); req.setServiceName("payment-svc");
        req.setEndpointPath("/api/pay"); req.setHttpMethod("POST"); req.setJsonPointer("/amount");

        when(vendorRepo.findById(1L)).thenReturn(Optional.of(VendorConfig.builder().id(1L).vendorName("Stripe").build()));
        when(dependencyRepo.existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
                1L, "payment-svc", "/api/pay", "POST", "/amount")).thenReturn(true);
        // Return a matching dependency so the nullSafeEquals passes
        var existing = ServiceDependency.builder().id(5L)
                .vendor(VendorConfig.builder().id(1L).vendorName("Stripe").build())
                .endpointPath("/api/pay").httpMethod("POST").jsonPointer("/amount")
                .serviceName("payment-svc").createdAt(OffsetDateTime.now()).build();
        when(dependencyRepo.findByVendorIdAndServiceName(1L, "payment-svc"))
                .thenReturn(List.of(existing));

        var r = controller.register(req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @DisplayName("GET dependencies with vendorId filter")
    void listDependenciesFiltered() {
        when(dependencyRepo.findByVendorId(1L)).thenReturn(List.of(dep(1L, 1L, "svc")));
        var r = controller.listDependencies(1L, null);
        assertThat(r).hasSize(1);
    }

    @Test @DisplayName("GET dependencies with serviceName filter")
    void listDependenciesByService() {
        when(dependencyRepo.findByServiceName("payment-svc"))
                .thenReturn(List.of(dep(1L, 1L, "payment-svc")));
        var r = controller.listDependencies(null, "payment-svc");
        assertThat(r).hasSize(1);
    }

    @Test @DisplayName("GET dependencies with both filters")
    void listDependenciesBothFilters() {
        when(dependencyRepo.findByVendorIdAndServiceName(1L, "svc"))
                .thenReturn(List.of(dep(1L, 1L, "svc")));
        var r = controller.listDependencies(1L, "svc");
        assertThat(r).hasSize(1);
    }

    @Test @DisplayName("GET dependencies with no filters")
    void listAllDependencies() {
        when(dependencyRepo.findAll()).thenReturn(List.of(dep(1L, 1L, "a"), dep(2L, 2L, "b")));
        var r = controller.listDependencies(null, null);
        assertThat(r).hasSize(2);
    }

    @Test @DisplayName("GET services/{vendorId} returns service names")
    void getConsumingServices() {
        when(dependencyRepo.findDistinctServiceNamesByVendorId(1L))
                .thenReturn(Set.of("payment-svc", "order-svc"));
        var r = controller.getConsumingServices(1L);
        assertThat(r).containsExactlyInAnyOrder("payment-svc", "order-svc");
    }

    @Test @DisplayName("DELETE dependencies/{id} returns 204")
    void deleteDependency() {
        when(dependencyRepo.existsById(1L)).thenReturn(true);
        assertThat(controller.deleteDependency(1L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(dependencyRepo).deleteById(1L);
    }

    @Test @DisplayName("DELETE dependencies/{id} returns 404 for missing")
    void deleteDependencyNotFound() {
        when(dependencyRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.deleteDependency(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
