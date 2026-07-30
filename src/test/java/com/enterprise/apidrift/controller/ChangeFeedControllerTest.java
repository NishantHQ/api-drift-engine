package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.entity.VendorHealthStatus;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.EncryptionService;
import com.enterprise.apidrift.service.VendorHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeFeedControllerTest {

    @Mock private com.enterprise.apidrift.repository.ChangeFingerprintRepository fingerprintRepo;

    @InjectMocks
    private ChangeFeedController controller;

    @Test
    @DisplayName("GET /changes/stats returns severity breakdown")
    void stats() {
        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of());
        when(fingerprintRepo.countDistinctVendorsWithActiveChanges()).thenReturn(0L);
        when(fingerprintRepo.countActiveBySeverity()).thenReturn(List.of());

        var r = controller.getStats();
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).containsKeys("totalActiveChanges", "vendorsWithActiveChanges", "bySeverity");
    }
}
