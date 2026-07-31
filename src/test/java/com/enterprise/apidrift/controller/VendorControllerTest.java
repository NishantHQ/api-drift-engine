package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.VendorConfigRequest;
import com.enterprise.apidrift.dto.VendorConfigResponse;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.entity.VendorHealthStatus;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.AuditLogService;
import com.enterprise.apidrift.service.EncryptionService;
import com.enterprise.apidrift.service.VendorHealthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendorControllerTest {

    @Mock private VendorConfigRepository vendorRepo;
    @Mock private EncryptionService encryptionService;
    @Mock private VendorHealthService healthService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private VendorController controller;

    private VendorConfig vendor(Long id, String name) {
        return VendorConfig.builder().id(id).vendorName(name).specUrl("https://" + name + ".com")
                .cronExpression("0 0 * * * *").isActive(true)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
    }

    @Test @DisplayName("listAll returns all vendors with pagination")
    void listAll() {
        List<VendorConfig> vendors = List.of(vendor(1L, "A"), vendor(2L, "B"));
        Page<VendorConfig> page = new PageImpl<>(vendors);
        when(vendorRepo.findAll(any(Pageable.class))).thenReturn(page);
        when(healthService.getStatus(any())).thenReturn(VendorHealthStatus.HEALTHY);
        ResponseEntity<Page<VendorConfigResponse>> result = controller.listAll(null, 0, 20);
        assertThat(result.getBody().getContent()).hasSize(2);
        assertThat(result.getBody().getContent().get(0).getHealthStatus()).isEqualTo("HEALTHY");
    }

    @Test @DisplayName("listAll filters by tag with pagination")
    void listAllByTag() {
        List<VendorConfig> vendors = List.of(vendor(1L, "Stripe"));
        Page<VendorConfig> page = new PageImpl<>(vendors);
        when(vendorRepo.findByTag(eq("payments"), any(Pageable.class))).thenReturn(page);
        when(healthService.getStatus(any())).thenReturn(VendorHealthStatus.HEALTHY);
        ResponseEntity<Page<VendorConfigResponse>> result = controller.listAll("payments", 0, 20);
        assertThat(result.getBody().getContent()).hasSize(1);
        assertThat(result.getBody().getContent().get(0).getVendorName()).isEqualTo("Stripe");
    }

    @Test @DisplayName("getById returns 200 for existing vendor")
    void getById() {
        when(vendorRepo.findById(1L)).thenReturn(Optional.of(vendor(1L, "Stripe")));
        when(healthService.getStatus(1L)).thenReturn(VendorHealthStatus.HEALTHY);
        var r = controller.getById(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getVendorName()).isEqualTo("Stripe");
    }

    @Test @DisplayName("getById returns 404 for missing")
    void getByIdNotFound() {
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.getById(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("create returns 201")
    void create() {
        var req = new VendorConfigRequest();
        req.setVendorName("Stripe"); req.setSpecUrl("https://s.com");
        req.setAuthToken("tok"); req.setCronExpression("0 0 * * * *"); req.setIsActive(true);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRemoteUser()).thenReturn("admin");
        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        when(vendorRepo.existsByVendorName("Stripe")).thenReturn(false);
        when(encryptionService.encrypt("tok")).thenReturn("enc");
        when(healthService.getStatus(1L)).thenReturn(VendorHealthStatus.HEALTHY);
        when(vendorRepo.save(any())).thenAnswer(inv -> {
            VendorConfig v = inv.getArgument(0);
            v.setId(1L);
            return v;
        });

        var r = controller.create(req, mockRequest);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().getVendorName()).isEqualTo("Stripe");
        assertThat(r.getBody().isAuthTokenConfigured()).isTrue();
    }

    @Test @DisplayName("create returns 409 on duplicate")
    void createDuplicate() {
        var req = new VendorConfigRequest();
        req.setVendorName("Stripe"); req.setSpecUrl("https://s.com");
        when(vendorRepo.existsByVendorName("Stripe")).thenReturn(true);
        assertThat(controller.create(req, mock(HttpServletRequest.class)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test @DisplayName("update returns 200")
    void update() {
        var req = new VendorConfigRequest();
        req.setVendorName("New"); req.setSpecUrl("https://n.com"); req.setIsActive(false);

        when(vendorRepo.findById(1L)).thenReturn(Optional.of(vendor(1L, "Old")));
        when(healthService.getStatus(1L)).thenReturn(VendorHealthStatus.HEALTHY);
        when(vendorRepo.save(any())).thenReturn(vendor(1L, "New"));

        var r = controller.update(1L, req);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(vendorRepo).save(any());
    }

    @Test @DisplayName("update returns 404 for missing vendor")
    void updateNotFound() {
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());
        var req = new VendorConfigRequest();
        req.setVendorName("X"); req.setSpecUrl("https://x.com");
        assertThat(controller.update(99L, req).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("delete returns 204")
    void deleteVendor() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        assertThat(controller.delete(1L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(vendorRepo).deleteById(1L);
    }

    @Test @DisplayName("delete returns 404 for missing")
    void deleteNotFound() {
        when(vendorRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.delete(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(vendorRepo, never()).deleteById(any());
    }

    @Test @DisplayName("getHealth returns status")
    void getHealth() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        when(healthService.getStatus(1L)).thenReturn(VendorHealthStatus.DOWN);
        var r = controller.getHealth(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("healthStatus", "DOWN");
    }

    @Test @DisplayName("getHealth returns 404 for missing vendor")
    void getHealthNotFound() {
        when(vendorRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.getHealth(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("resetHealth returns 200")
    void resetHealth() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        var r = controller.resetHealth(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("healthStatus", "HEALTHY");
        verify(healthService).reset(1L);
    }
}
