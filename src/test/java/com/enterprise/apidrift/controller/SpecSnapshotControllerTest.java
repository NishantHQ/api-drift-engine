package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.SpecSnapshotResponse;
import com.enterprise.apidrift.entity.SpecSnapshot;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.SpecSnapshotRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecSnapshotControllerTest {

    @Mock private SpecSnapshotRepository snapshotRepo;
    @Mock private VendorConfigRepository vendorRepo;

    @InjectMocks
    private SpecSnapshotController controller;

    private VendorConfig vendor() {
        return VendorConfig.builder().id(1L).vendorName("Stripe")
                .specUrl("https://stripe.com").isActive(true).build();
    }

    private SpecSnapshot snapshot(Long id, String hash) {
        return SpecSnapshot.builder()
                .id(id).vendor(vendor()).contentHash(hash)
                .specVersion("2024-01-01").rawSpec("{\"openapi\":\"3.0\"}")
                .createdAt(OffsetDateTime.now()).build();
    }

    @Test @DisplayName("listByVendor returns snapshots without rawSpec with pagination")
    void listByVendor() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        List<SpecSnapshot> snapshots = List.of(snapshot(2L, "def"), snapshot(1L, "abc"));
        Page<SpecSnapshot> page = new PageImpl<>(snapshots);
        when(snapshotRepo.findByVendorIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                .thenReturn(page);

        var result = controller.listByVendor(1L, 0, 20);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).hasSize(2);
        assertThat(result.getBody().getContent().get(0).getRawSpec()).isNull();
        assertThat(result.getBody().getContent().get(0).getContentHash()).isEqualTo("def");
    }

    @Test @DisplayName("listByVendor returns 404 for missing vendor")
    void listByVendorNotFound() {
        when(vendorRepo.existsById(99L)).thenReturn(false);
        assertThat(controller.listByVendor(99L, 0, 20).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("getLatest returns snapshot with rawSpec")
    void getLatest() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        when(snapshotRepo.findLatestByVendorId(1L))
                .thenReturn(Optional.of(snapshot(3L, "ghi")));

        var result = controller.getLatest(1L);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getRawSpec()).isEqualTo("{\"openapi\":\"3.0\"}");
        assertThat(result.getBody().getVendorName()).isEqualTo("Stripe");
    }

    @Test @DisplayName("getLatest returns 404 when no snapshots exist")
    void getLatestEmpty() {
        when(vendorRepo.existsById(1L)).thenReturn(true);
        when(snapshotRepo.findLatestByVendorId(1L)).thenReturn(Optional.empty());

        assertThat(controller.getLatest(1L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("getById returns snapshot with rawSpec")
    void getById() {
        when(snapshotRepo.findById(2L)).thenReturn(Optional.of(snapshot(2L, "def")));

        var result = controller.getById(1L, 2L);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContentHash()).isEqualTo("def");
        assertThat(result.getBody().getRawSpec()).isEqualTo("{\"openapi\":\"3.0\"}");
    }

    @Test @DisplayName("getById returns 404 when vendorId does not match")
    void getByIdVendorMismatch() {
        // snapshot belongs to vendor 1, but request is for vendor 99
        when(snapshotRepo.findById(2L)).thenReturn(Optional.of(snapshot(2L, "def")));

        assertThat(controller.getById(99L, 2L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("getById returns 404 for missing snapshot")
    void getByIdNotFound() {
        when(snapshotRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.getById(1L, 99L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
