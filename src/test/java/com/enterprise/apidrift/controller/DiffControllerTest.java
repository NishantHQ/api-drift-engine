package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.entity.*;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.IngestionOrchestrator;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiffControllerTest {

    @Mock private IngestionOrchestrator orchestrator;
    @Mock private VendorConfigRepository vendorRepo;
    @Mock private DiffAuditRunRepository auditRepo;
    @Mock private ChangeFingerprintRepository fingerprintRepo;

    @InjectMocks
    private DiffController controller;

    private VendorConfig vendor() {
        return VendorConfig.builder().id(1L).vendorName("Stripe").build();
    }

    @Test @DisplayName("POST trigger/{vendorId} returns 404 for missing vendor")
    void triggerNotFound() {
        when(vendorRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.triggerDiff(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("POST trigger/{vendorId} returns 200 with audit run")
    void triggerSuccess() {
        var vendor = vendor();
        var run = DiffAuditRun.builder().id(10L).vendor(vendor)
                .totalChanges(3).breakingChanges(1).status(RunStatus.SUCCESS)
                .executedAt(OffsetDateTime.now()).build();

        when(vendorRepo.findById(1L)).thenReturn(Optional.of(vendor));
        when(orchestrator.runPipeline(vendor)).thenReturn(run);
        when(fingerprintRepo.findByAuditRunId(10L)).thenReturn(List.of());

        var r = controller.triggerDiff(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getAuditRunId()).isEqualTo(10L);
        assertThat(r.getBody().getStatus()).isEqualTo("SUCCESS");
    }

    @Test @DisplayName("GET history/{vendorId} returns 404 for missing vendor")
    void historyNotFound() {
        when(vendorRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.getHistory(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("GET history/{vendorId} returns audit runs")
    void historySuccess() {
        var vendor = vendor();
        var run = DiffAuditRun.builder().id(1L).vendor(vendor)
                .totalChanges(2).breakingChanges(1).status(RunStatus.SUCCESS)
                .executedAt(OffsetDateTime.now()).build();

        when(vendorRepo.existsById(1L)).thenReturn(true);
        when(auditRepo.findByVendorIdOrderByExecutedAtDesc(1L)).thenReturn(List.of(run));

        var r = controller.getHistory(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(r.getBody().get(0).getVendorName()).isEqualTo("Stripe");
    }

    @Test @DisplayName("GET active/{vendorId} returns 404 for missing vendor")
    void activeNotFound() {
        when(vendorRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.getActiveChanges(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("GET active/{vendorId} returns active changes")
    void activeSuccess() {
        var fp = ChangeFingerprint.builder()
                .id(1L).fingerprintHash("abc").changeType("PARAM_REMOVED")
                .severity(ChangeSeverity.HIGH).httpMethod("GET").endpointPath("/api")
                .jsonPointer("/x").description("Test").isActive(true).build();

        when(vendorRepo.existsById(1L)).thenReturn(true);
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(1L)).thenReturn(List.of(fp));

        var r = controller.getActiveChanges(1L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
    }

    @Test @DisplayName("POST resolve/{id} returns 404 for missing fingerprint")
    void resolveNotFound() {
        when(fingerprintRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.resolveChange(99L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("POST resolve/{id} resolves and returns change")
    void resolveSuccess() {
        var fp = ChangeFingerprint.builder()
                .id(1L).fingerprintHash("abc").changeType("PARAM_REMOVED")
                .severity(ChangeSeverity.HIGH).httpMethod("GET").endpointPath("/api")
                .jsonPointer("/x").description("Removed").isActive(true).build();

        when(fingerprintRepo.findById(1L)).thenReturn(Optional.of(fp));

        var r = controller.resolveChange(1L, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getFingerprintHash()).isEqualTo("abc");
    }
}
