package com.enterprise.apidrift.engine.telemetry;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageCorrelationServiceTest {

    @Mock private TelemetryRegistry telemetryRegistry;

    @InjectMocks
    private UsageCorrelationService correlationService;

    private DetectedChange breakingChange;
    private DetectedChange nonBreakingChange;
    private static final Long VENDOR_ID = 1L;

    @BeforeEach
    void setUp() {
        breakingChange = DetectedChange.builder()
                .changeType("PARAM_REMOVED")
                .severity(ChangeSeverity.HIGH)
                .direction("REQUEST")
                .httpMethod("GET")
                .endpointPath("/v1/charges")
                .jsonPointer("/parameters/id")
                .description("Parameter removed")
                .isBreaking(true)
                .build();

        nonBreakingChange = DetectedChange.builder()
                .changeType("OPTIONAL_PARAM_ADDED")
                .severity(ChangeSeverity.INFO)
                .direction("REQUEST")
                .httpMethod("GET")
                .endpointPath("/v1/charges")
                .jsonPointer("/parameters/page")
                .description("Optional param added")
                .isBreaking(false)
                .build();
    }

    @Test
    @DisplayName("Breaking change consumed by service → escalated to CRITICAL")
    void consumedBreakingChangeEscalatedToCritical() {
        when(telemetryRegistry.findConsumers(eq(VENDOR_ID), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("payment-service"));

        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of(breakingChange));

        assertThat(changes.get(0).getSeverity()).isEqualTo(ChangeSeverity.CRITICAL);
        assertThat(changes.get(0).getConsumingService()).isEqualTo("payment-service");
    }

    @Test
    @DisplayName("Breaking change NOT consumed → downgraded to LOW")
    void unconsumedBreakingChangeDowngradedToLow() {
        when(telemetryRegistry.findConsumers(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of());

        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of(breakingChange));

        assertThat(changes.get(0).getSeverity()).isEqualTo(ChangeSeverity.LOW);
        assertThat(changes.get(0).getConsumingService()).isNull();
    }

    @Test
    @DisplayName("Non-breaking change consumed → severity unchanged")
    void consumedNonBreakingUnchanged() {
        when(telemetryRegistry.findConsumers(eq(VENDOR_ID), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("payment-service"));

        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of(nonBreakingChange));

        assertThat(changes.get(0).getSeverity()).isEqualTo(ChangeSeverity.INFO);
        assertThat(changes.get(0).getConsumingService()).isEqualTo("payment-service");
    }

    @Test
    @DisplayName("Non-breaking change not consumed → severity unchanged")
    void unconsumedNonBreakingUnchanged() {
        when(telemetryRegistry.findConsumers(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Set.of());

        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of(nonBreakingChange));

        assertThat(changes.get(0).getSeverity()).isEqualTo(ChangeSeverity.INFO);
    }

    @Test
    @DisplayName("Multiple consuming services are joined with comma")
    void multipleConsumersJoined() {
        when(telemetryRegistry.findConsumers(eq(VENDOR_ID), anyString(), anyString(), anyString()))
                .thenReturn(Set.of("payment-service", "order-service", "user-service"));

        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of(breakingChange));

        assertThat(changes.get(0).getConsumingService()).contains("payment-service", "order-service", "user-service");
    }

    @Test
    @DisplayName("Empty changes list returns empty")
    void emptyChangesReturnsEmpty() {
        List<DetectedChange> changes = correlationService.correlateWithUsage(VENDOR_ID, List.of());
        assertThat(changes).isEmpty();
    }
}
