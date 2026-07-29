package com.enterprise.apidrift.service;

import com.enterprise.apidrift.entity.VendorHealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for VendorHealthService circuit breaker and retry behavior.
 */
class VendorHealthServiceTest {

    private VendorHealthService healthService;

    private static final Long VENDOR_ID = 1L;
    private static final Long OTHER_VENDOR = 2L;

    @BeforeEach
    void setUp() {
        healthService = new VendorHealthService();
        // Default: 1-minute cooldown so DOWN state is observable in tests
        ReflectionTestUtils.setField(healthService, "cooldownMinutes", 1L);
    }

    // --- Initial state ---

    @Test
    @DisplayName("Initial state is HEALTHY")
    void initialStatusIsHealthy() {
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.HEALTHY);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isFalse();
    }

    @Test
    @DisplayName("Null vendorId returns HEALTHY")
    void nullVendorIdReturnsHealthy() {
        assertThat(healthService.getStatus(null)).isEqualTo(VendorHealthStatus.HEALTHY);
        assertThat(healthService.isCircuitOpen(null)).isFalse();
    }

    // --- Failure progression ---

    @Test
    @DisplayName("Single failure transitions to DEGRADED")
    void singleFailureTransitionsToDegraded() {
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isFalse();
    }

    @Test
    @DisplayName("Two consecutive failures remain DEGRADED")
    void twoFailuresRemainDegraded() {
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isFalse();
    }

    @Test
    @DisplayName("Three consecutive failures open the circuit (DOWN)")
    void threeFailuresOpensCircuit() {
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        boolean shouldRetry = healthService.recordFailure(VENDOR_ID);

        assertThat(shouldRetry).isFalse();
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DOWN);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isTrue();
    }

    // --- Recovery ---

    @Test
    @DisplayName("Success after failures resets to HEALTHY")
    void successResetsToHealthy() {
        // Degrade
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);

        // Recover
        healthService.recordSuccess(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.HEALTHY);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isFalse();
    }

    @Test
    @DisplayName("Success after circuit open resets to HEALTHY")
    void successAfterCircuitOpenResets() {
        // Open circuit
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DOWN);

        // Recover
        healthService.recordSuccess(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.HEALTHY);
    }

    @Test
    @DisplayName("Circuit half-opens (DEGRADED) after cooldown elapses")
    void circuitHalfOpensAfterCooldown() {
        // Open circuit with non-zero cooldown
        ReflectionTestUtils.setField(healthService, "cooldownMinutes", 30L);
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DOWN);

        // Fast-forward: set cooldown to 0 so it's technically elapsed
        ReflectionTestUtils.setField(healthService, "cooldownMinutes", 0L);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);
    }

    // --- Backoff values ---

    @Test
    @DisplayName("Backoff delays follow exponential pattern: 1s, 2s, 4s")
    void backoffDelaysAreExponential() {
        assertThat(healthService.getBackoffMs(0)).isEqualTo(1_000L);
        assertThat(healthService.getBackoffMs(1)).isEqualTo(2_000L);
        assertThat(healthService.getBackoffMs(2)).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("Max retries is 3")
    void maxRetriesIsThree() {
        assertThat(healthService.getMaxRetries()).isEqualTo(3);
    }

    // --- Isolation ---

    @Test
    @DisplayName("Vendors are tracked independently — failure on one doesn't affect another")
    void vendorIsolation() {
        // Open circuit for vendor 1
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DOWN);

        // Vendor 2 unaffected
        assertThat(healthService.getStatus(OTHER_VENDOR)).isEqualTo(VendorHealthStatus.HEALTHY);

        // Vendor 2 degrades independently
        healthService.recordFailure(OTHER_VENDOR);
        assertThat(healthService.getStatus(OTHER_VENDOR)).isEqualTo(VendorHealthStatus.DEGRADED);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DOWN);
    }

    // --- Reset ---

    @Test
    @DisplayName("Reset clears circuit state to HEALTHY")
    void resetClearsState() {
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isTrue();

        healthService.reset(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.HEALTHY);
        assertThat(healthService.isCircuitOpen(VENDOR_ID)).isFalse();
    }

    @Test
    @DisplayName("Reset with null vendorId is a no-op")
    void resetWithNullIsNoop() {
        healthService.recordFailure(VENDOR_ID);
        healthService.reset(null);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);
    }

    // --- recordSuccess / recordFailure with null ---

    @Test
    @DisplayName("recordSuccess with null vendorId is a no-op")
    void recordSuccessWithNullIsNoop() {
        // Should not throw
        healthService.recordSuccess(null);
    }

    @Test
    @DisplayName("recordFailure with null vendorId returns true (allow retry)")
    void recordFailureWithNullReturnsTrue() {
        boolean shouldRetry = healthService.recordFailure(null);
        assertThat(shouldRetry).isTrue();
    }

    // --- Interleaved success/failure ---

    @Test
    @DisplayName("Single failure then success returns to HEALTHY, subsequent failure degrades again")
    void interleavedSuccessAndFailure() {
        // Failure → DEGRADED
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);

        // Success → HEALTHY
        healthService.recordSuccess(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.HEALTHY);

        // Failure again → DEGRADED (not immediately DOWN)
        healthService.recordFailure(VENDOR_ID);
        assertThat(healthService.getStatus(VENDOR_ID)).isEqualTo(VendorHealthStatus.DEGRADED);
    }
}
