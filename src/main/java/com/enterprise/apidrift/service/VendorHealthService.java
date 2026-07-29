package com.enterprise.apidrift.service;

import com.enterprise.apidrift.entity.VendorHealthStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks vendor spec-fetch health and implements a circuit breaker:
 * - 3 retries per fetch attempt with exponential backoff (1s, 2s, 4s)
 * - After 3 consecutive fetch failures, circuit opens → vendor DOWN for 30 min
 * - After cooldown, circuit half-opens on next poll → success restores HEALTHY
 * - 1-2 consecutive failures → DEGRADED (still polled but flagged)
 */
@Slf4j
@Service
public class VendorHealthService {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    static final int CIRCUIT_OPEN_THRESHOLD = 3;

    @Value("${vendor-health.cooldown-minutes:30}")
    private long cooldownMinutes = 30;

    private final Map<Long, VendorHealthState> states = new ConcurrentHashMap<>();

    /**
     * Returns the current health status for a vendor.
     * Returns HEALTHY for null vendorId (e.g., ad-hoc fetches without a vendor record).
     */
    public VendorHealthStatus getStatus(Long vendorId) {
        if (vendorId == null) return VendorHealthStatus.HEALTHY;
        VendorHealthState state = states.get(vendorId);
        if (state == null) return VendorHealthStatus.HEALTHY;

        if (state.consecutiveFailures >= CIRCUIT_OPEN_THRESHOLD) {
            // Check if cooldown has elapsed
            if (state.lastFailureAt != null) {
                long minutesSinceLastFailure = java.time.Duration
                        .between(state.lastFailureAt, OffsetDateTime.now())
                        .toMinutes();
                if (minutesSinceLastFailure >= cooldownMinutes) {
                    // Circuit half-opens
                    return VendorHealthStatus.DEGRADED;
                }
            }
            return VendorHealthStatus.DOWN;
        }

        return state.consecutiveFailures > 0
                ? VendorHealthStatus.DEGRADED
                : VendorHealthStatus.HEALTHY;
    }

    /**
     * Whether the vendor should be skipped entirely (circuit open and in cooldown).
     */
    public boolean isCircuitOpen(Long vendorId) {
        if (vendorId == null) return false;
        return getStatus(vendorId) == VendorHealthStatus.DOWN;
    }

    /**
     * Record a successful fetch.
     */
    public void recordSuccess(Long vendorId) {
        if (vendorId == null) return;
        VendorHealthState state = states.computeIfAbsent(vendorId, k -> new VendorHealthState());
        int previousFailures = state.consecutiveFailures;
        state.consecutiveFailures = 0;
        state.lastSuccessAt = OffsetDateTime.now();
        state.totalFailures = 0; // reset on success
        if (previousFailures > 0) {
            log.info("Vendor {} circuit closed — fetch succeeded after {} consecutive failures",
                    vendorId, previousFailures);
        }
    }

    /**
     * Record a failed fetch attempt. Returns true if retry should be attempted.
     */
    public boolean recordFailure(Long vendorId) {
        if (vendorId == null) return true; // retry with no circuit tracking
        VendorHealthState state = states.computeIfAbsent(vendorId, k -> new VendorHealthState());
        state.consecutiveFailures++;
        state.totalFailures++;
        state.lastFailureAt = OffsetDateTime.now();

        if (state.consecutiveFailures >= CIRCUIT_OPEN_THRESHOLD) {
            log.warn("Vendor {} circuit OPEN — {} consecutive fetch failures, pausing for {} min",
                    vendorId, state.consecutiveFailures, cooldownMinutes);
            return false; // stop retrying
        }

        log.warn("Vendor {} fetch failure {}/{} ({} total) — will retry",
                vendorId, state.consecutiveFailures, CIRCUIT_OPEN_THRESHOLD, state.totalFailures);
        return true; // still retrying
    }

    /**
     * Get the backoff delay in milliseconds for a retry attempt (0-indexed).
     */
    public long getBackoffMs(int retryAttempt) {
        return INITIAL_BACKOFF_MS * (1L << retryAttempt); // 1s, 2s, 4s
    }

    /**
     * Maximum number of retry attempts per fetch.
     */
    public int getMaxRetries() {
        return MAX_RETRIES;
    }

    /**
     * Reset health for a vendor (e.g., after manual intervention).
     */
    public void reset(Long vendorId) {
        if (vendorId == null) return;
        states.remove(vendorId);
        log.info("Vendor {} health state reset", vendorId);
    }

    // --- internal state holder ---

    private static class VendorHealthState {
        int consecutiveFailures = 0;
        int totalFailures = 0;
        OffsetDateTime lastFailureAt;
        OffsetDateTime lastSuccessAt;
    }
}
