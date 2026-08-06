package com.enterprise.apidrift.service;

import com.enterprise.apidrift.entity.AuditLogEntry;
import com.enterprise.apidrift.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Writes audit log entries asynchronously to avoid blocking the main request thread.
 * Logs admin actions: vendor CRUD, manual diff triggers, health resets,
 * dependency registrations, fingerprint resolutions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepo;

    @Async("taskExecutor")
    public void log(String actor, String action, String resourceType,
                    Long resourceId, String detail, String ipAddress) {
        try {
            AuditLogEntry entry = AuditLogEntry.builder()
                    .actor(actor)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .detail(detail)
                    .ipAddress(ipAddress)
                    .createdAt(OffsetDateTime.now())
                    .build();
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.error("Failed to write audit log entry: action={}, resource={}/{}",
                    action, resourceType, resourceId, e);
        }
    }

    /** Convenience overload without IP address. */
    public void log(String actor, String action, String resourceType,
                    Long resourceId, String detail) {
        log(actor, action, resourceType, resourceId, detail, null);
    }
}
