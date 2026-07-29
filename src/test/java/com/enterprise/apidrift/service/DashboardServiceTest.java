package com.enterprise.apidrift.service;

import com.enterprise.apidrift.engine.telemetry.TelemetryRegistry;
import com.enterprise.apidrift.entity.*;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private VendorConfigRepository vendorRepo;
    @Mock private ChangeFingerprintRepository fingerprintRepo;
    @Mock private DiffAuditRunRepository auditRepo;
    @Mock private TelemetryRegistry telemetryRegistry;

    @InjectMocks
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        when(vendorRepo.count()).thenReturn(5L);
        when(vendorRepo.findByIsActiveTrue()).thenReturn(
                List.of(buildVendor(1L, "Stripe"), buildVendor(2L, "Shopify"), buildVendor(3L, "GitHub")));
    }

    @Test
    @DisplayName("Dashboard returns vendor counts")
    void vendorCounts() {
        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of());
        when(auditRepo.findTop10ByOrderByExecutedAtDesc()).thenReturn(List.of());

        var dash = dashboardService.buildDashboard();

        assertThat(dash.getTotalVendors()).isEqualTo(5);
        assertThat(dash.getActiveVendors()).isEqualTo(3);
        assertThat(dash.getActiveBreakingChanges()).isEqualTo(0);
        assertThat(dash.getVendorsWithActiveChanges()).isEqualTo(0);
    }

    @Test
    @DisplayName("Dashboard returns severity breakdown for active changes")
    void severityBreakdown() {
        var fp1 = buildFingerprint(1L, ChangeSeverity.CRITICAL, "/api/payments", "POST");
        var fp2 = buildFingerprint(1L, ChangeSeverity.HIGH, "/api/payments", "GET");
        var fp3 = buildFingerprint(2L, ChangeSeverity.HIGH, "/api/orders", "GET");

        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of(fp1, fp2, fp3));
        when(auditRepo.findTop10ByOrderByExecutedAtDesc()).thenReturn(List.of());

        var dash = dashboardService.buildDashboard();

        assertThat(dash.getActiveBreakingChanges()).isEqualTo(3);
        assertThat(dash.getChangesBySeverity())
                .containsEntry("CRITICAL", 1)
                .containsEntry("HIGH", 2)
                .containsEntry("MEDIUM", 0)
                .containsEntry("LOW", 0)
                .containsEntry("INFO", 0);
        assertThat(dash.getVendorsWithActiveChanges()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dashboard returns impacted services")
    void impactedServices() {
        var fp = buildFingerprint(1L, ChangeSeverity.CRITICAL, "/api/payments", "POST");

        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of(fp));
        when(auditRepo.findTop10ByOrderByExecutedAtDesc()).thenReturn(List.of());
        when(telemetryRegistry.findConsumers(eq(1L), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("payment-service", "order-service"));

        var dash = dashboardService.buildDashboard();

        assertThat(dash.getMostImpactedServices()).hasSize(2);
        assertThat(dash.getMostImpactedServices().get(0).getServiceName()).isIn("payment-service", "order-service");
        assertThat(dash.getMostImpactedServices().get(0).getAffectedEndpoints()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dashboard returns recent drift activity")
    void recentActivity() {
        var vendor = buildVendor(1L, "Stripe");
        var run = DiffAuditRun.builder()
                .id(100L).vendor(vendor)
                .totalChanges(5).breakingChanges(2)
                .status(RunStatus.SUCCESS)
                .executedAt(OffsetDateTime.now())
                .build();

        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of());
        when(auditRepo.findTop10ByOrderByExecutedAtDesc()).thenReturn(List.of(run));

        var dash = dashboardService.buildDashboard();

        assertThat(dash.getRecentDriftActivity()).hasSize(1);
        assertThat(dash.getRecentDriftActivity().get(0).getVendorName()).isEqualTo("Stripe");
        assertThat(dash.getRecentDriftActivity().get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(dash.getRecentDriftActivity().get(0).getBreakingChanges()).isEqualTo(2);
    }

    @Test
    @DisplayName("Dashboard handles empty state gracefully")
    void emptyState() {
        when(fingerprintRepo.findByIsActiveTrue()).thenReturn(List.of());
        when(auditRepo.findTop10ByOrderByExecutedAtDesc()).thenReturn(List.of());

        var dash = dashboardService.buildDashboard();

        assertThat(dash.getActiveBreakingChanges()).isEqualTo(0);
        assertThat(dash.getMostImpactedServices()).isEmpty();
        assertThat(dash.getRecentDriftActivity()).isEmpty();
        assertThat(dash.getVendorsWithActiveChanges()).isEqualTo(0);
    }

    // --- helpers ---

    private VendorConfig buildVendor(Long id, String name) {
        return VendorConfig.builder().id(id).vendorName(name).build();
    }

    private ChangeFingerprint buildFingerprint(Long vendorId, ChangeSeverity severity,
                                                String endpoint, String method) {
        return ChangeFingerprint.builder()
                .id(vendorId * 100 + method.hashCode())
                .vendor(buildVendor(vendorId, "Vendor" + vendorId))
                .severity(severity)
                .endpointPath(endpoint)
                .httpMethod(method)
                .jsonPointer("/properties/x")
                .changeType("TEST_CHANGE")
                .description("Test")
                .isActive(true)
                .fingerprintHash("hash-" + vendorId + "-" + method)
                .build();
    }
}
