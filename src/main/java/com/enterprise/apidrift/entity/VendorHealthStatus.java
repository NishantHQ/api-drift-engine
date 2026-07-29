package com.enterprise.apidrift.entity;

/**
 * Circuit-breaker health status for vendor spec fetching.
 *
 * HEALTHY  — no recent failures, spec fetching is normal
 * DEGRADED — 1-2 consecutive failures, backoff is active but vendor is still polled
 * DOWN     — 3+ consecutive failures, vendor is paused (circuit open) for a cooldown period
 */
public enum VendorHealthStatus {
    HEALTHY,
    DEGRADED,
    DOWN
}
