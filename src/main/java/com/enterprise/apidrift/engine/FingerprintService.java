package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.enterprise.apidrift.entity.DiffAuditRun;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates deterministic SHA-256 fingerprints for detected changes and
 * manages deduplication state (NEW, ACTIVE, RESOLVED).
 *
 * Fingerprint formula (BRD FR-5.1):
 *   SHA256(VendorID + ":" + EndpointPath + ":" + HTTPMethod + ":" + ChangeType + ":" + JSONPointer)
 *
 * State management (BRD FR-5.2):
 *   - Alerts fire only when a fingerprint transitions to NEW or RESOLVED
 *   - Repeated identical changes in subsequent runs do NOT re-trigger
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FingerprintService {

    private final ChangeFingerprintRepository fingerprintRepository;

    /**
     * Generates a SHA-256 fingerprint for a detected change.
     */
    public String generateFingerprint(Long vendorId, DetectedChange change) {
        String input = vendorId + ":"
                + (change.getEndpointPath() != null ? change.getEndpointPath() : "") + ":"
                + (change.getHttpMethod() != null ? change.getHttpMethod() : "") + ":"
                + change.getChangeType() + ":"
                + (change.getJsonPointer() != null ? change.getJsonPointer() : "");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Processes detected changes, assigning fingerprints, checking against
     * existing state, and returning only the changes that should fire alerts.
     *
     * @return changes that are NEW or RESOLVED (should trigger notification)
     */
    @Transactional
    public List<DetectedChange> deduplicateAndFilter(Long vendorId,
                                                      List<DetectedChange> changes,
                                                      DiffAuditRun auditRun) {
        List<DetectedChange> alertableChanges = new ArrayList<>();

        for (DetectedChange change : changes) {
            String hash = generateFingerprint(vendorId, change);
            change.setFingerprintHash(hash);

            var existingOpt = fingerprintRepository.findActiveByVendorAndHash(vendorId, hash);

            if (existingOpt.isPresent()) {
                ChangeFingerprint existing = existingOpt.get();
                // Already ACTIVE — update last_seen_at, no re-alert
                existing.setLastSeenAt(OffsetDateTime.now());
                existing.setAuditRun(auditRun);
                fingerprintRepository.save(existing);
                log.debug("Fingerprint {} already active, suppressing duplicate alert", hash);
            } else {
                // Check if previously RESOLVED (exists but inactive)
                var resolvedOpt = fingerprintRepository
                        .findByVendorIdAndFingerprintHash(vendorId, hash);

                if (resolvedOpt.isPresent()) {
                    // RESOLVED → re-activate → ALERT
                    ChangeFingerprint resolved = resolvedOpt.get();
                    resolved.setIsActive(true);
                    resolved.setLastSeenAt(OffsetDateTime.now());
                    resolved.setAuditRun(auditRun);
                    resolved.setSeverity(change.getSeverity());
                    resolved.setDescription(change.getDescription());
                    fingerprintRepository.save(resolved);
                    alertableChanges.add(change);
                    log.info("Fingerprint {} RESOLVED → re-activated, alerting", hash);
                } else {
                    // NEW → create and ALERT
                    ChangeFingerprint fp = ChangeFingerprint.builder()
                            .auditRun(auditRun)
                            .vendor(VendorConfig.builder().id(vendorId).build())
                            .fingerprintHash(hash)
                            .changeType(change.getChangeType())
                            .severity(change.getSeverity())
                            .httpMethod(change.getHttpMethod())
                            .endpointPath(change.getEndpointPath())
                            .jsonPointer(change.getJsonPointer())
                            .description(change.getDescription())
                            .isActive(true)
                            .firstSeenAt(OffsetDateTime.now())
                            .lastSeenAt(OffsetDateTime.now())
                            .build();
                    fingerprintRepository.save(fp);
                    alertableChanges.add(change);
                    log.info("New fingerprint {} created, alerting", hash);
                }
            }
        }

        // Resolve fingerprints that are no longer present in this run
        List<ChangeFingerprint> activeFingerprints =
                fingerprintRepository.findByVendorIdAndIsActiveTrue(vendorId);
        for (ChangeFingerprint active : activeFingerprints) {
            boolean stillPresent = changes.stream()
                    .anyMatch(c -> active.getFingerprintHash().equals(c.getFingerprintHash()));
            if (!stillPresent) {
                active.setIsActive(false);
                active.setLastSeenAt(OffsetDateTime.now());
                // Preserve any manual resolution metadata already set
                fingerprintRepository.save(active);
                log.info("Fingerprint {} auto-resolved (no longer detected){}",
                        active.getFingerprintHash(),
                        active.getResolvedBy() != null ? " — was manually resolved by " + active.getResolvedBy() : "");
            }
        }

        log.info("Dedup complete: {} total changes, {} alertable",
                changes.size(), alertableChanges.size());
        return alertableChanges;
    }
}
