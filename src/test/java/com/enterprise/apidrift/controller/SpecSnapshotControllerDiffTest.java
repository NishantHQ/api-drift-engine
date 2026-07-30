package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.engine.DirectionalCompatibilityEvaluator;
import com.enterprise.apidrift.engine.OpenApiNormalizationService;
import com.enterprise.apidrift.entity.SpecSnapshot;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.SpecSnapshotRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class SpecSnapshotControllerDiffTest {

    @Mock private SpecSnapshotRepository snapshotRepo;
    @Mock private VendorConfigRepository vendorRepo;
    @Mock private OpenApiNormalizationService normalizationService;
    @Mock private DirectionalCompatibilityEvaluator compatibilityEvaluator;

    @InjectMocks
    private SpecSnapshotController controller;

    private final ObjectMapper mapper = new ObjectMapper();

    private VendorConfig vendor() {
        return VendorConfig.builder().id(1L).vendorName("Stripe")
                .specUrl("https://stripe.com").isActive(true).build();
    }

    private SpecSnapshot snapshot(Long id, String rawSpec) {
        return SpecSnapshot.builder()
                .id(id).vendor(vendor()).contentHash("hash" + id)
                .specVersion("3.0").rawSpec(rawSpec)
                .createdAt(OffsetDateTime.now()).build();
    }

    @Test @DisplayName("diff returns 404 for missing old snapshot")
    void diffOldNotFound() {
        when(snapshotRepo.findById(99L)).thenReturn(Optional.empty());
        when(snapshotRepo.findById(2L)).thenReturn(Optional.of(snapshot(2L, "{}")));
        assertThat(controller.compareSnapshots(1L, 99L, 2L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("diff returns 404 for vendor mismatch")
    void diffVendorMismatch() {
        var old = snapshot(1L, "{}");
        var otherVendor = VendorConfig.builder().id(99L).vendorName("Other")
                .specUrl("https://o.com").isActive(true).build();
        var newSnap = SpecSnapshot.builder()
                .id(2L).vendor(otherVendor).contentHash("h2")
                .specVersion("3.0").rawSpec("{}")
                .createdAt(OffsetDateTime.now()).build();

        when(snapshotRepo.findById(1L)).thenReturn(Optional.of(old));
        when(snapshotRepo.findById(2L)).thenReturn(Optional.of(newSnap));

        assertThat(controller.compareSnapshots(1L, 1L, 2L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("diff returns 200 with changes for valid snapshots")
    void diffSuccess() throws Exception {
        String oldRaw = "{\"openapi\":\"3.0\"}";
        String newRaw = "{\"openapi\":\"3.1\"}";
        var old = snapshot(1L, oldRaw);
        var newSnap = snapshot(2L, newRaw);

        JsonNode oldNode = mapper.readTree(oldRaw);
        JsonNode newNode = mapper.readTree(newRaw);

        when(snapshotRepo.findById(1L)).thenReturn(Optional.of(old));
        when(snapshotRepo.findById(2L)).thenReturn(Optional.of(newSnap));
        when(normalizationService.parseAndNormalize(oldRaw)).thenReturn(oldNode);
        when(normalizationService.parseAndNormalize(newRaw)).thenReturn(newNode);
        when(compatibilityEvaluator.evaluate(any(), any())).thenReturn(List.of());

        var r = controller.compareSnapshots(1L, 1L, 2L);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().getOldSnapshot()).isNotNull();
        assertThat(r.getBody().getNewSnapshot()).isNotNull();
        assertThat(r.getBody().getOldSnapshot().getRawSpec()).isEqualTo(oldRaw);
        assertThat(r.getBody().getNewSnapshot().getRawSpec()).isEqualTo(newRaw);
    }
}
