package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.entity.ChangeSeverity;
import com.enterprise.apidrift.entity.DiffAuditRun;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FingerprintService — SHA-256 generation and dedup state machine.
 */
@ExtendWith(MockitoExtension.class)
class FingerprintServiceTest {

    @Mock
    private ChangeFingerprintRepository fingerprintRepo;

    @InjectMocks
    private FingerprintService fingerprintService;

    @Captor
    private ArgumentCaptor<ChangeFingerprint> fingerprintCaptor;

    private DiffAuditRun auditRun;
    private static final Long VENDOR_ID = 1L;

    @BeforeEach
    void setUp() {
        auditRun = DiffAuditRun.builder()
                .id(100L)
                .vendor(VendorConfig.builder().id(VENDOR_ID).vendorName("TestVendor").build())
                .build();
    }

    // ── Fingerprint generation ──────────────────────────────────

    @Test
    @DisplayName("SHA-256 fingerprint is deterministic")
    void fingerprintIsDeterministic() {
        DetectedChange change = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");

        String hash1 = fingerprintService.generateFingerprint(VENDOR_ID, change);
        String hash2 = fingerprintService.generateFingerprint(VENDOR_ID, change);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex is 64 chars
    }

    @Test
    @DisplayName("Different changes produce different fingerprints")
    void differentChangesDifferentFingerprints() {
        DetectedChange change1 = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");
        DetectedChange change2 = makeChange("POST", "/users", "PARAM_ADDED", "#/params/name");

        String hash1 = fingerprintService.generateFingerprint(VENDOR_ID, change1);
        String hash2 = fingerprintService.generateFingerprint(VENDOR_ID, change2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Different vendor IDs produce different fingerprints for same change")
    void differentVendorDifferentFingerprint() {
        DetectedChange change = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");

        String hash1 = fingerprintService.generateFingerprint(1L, change);
        String hash2 = fingerprintService.generateFingerprint(2L, change);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ── NEW fingerprints → alert ────────────────────────────────

    @Test
    @DisplayName("New fingerprint → created, added to alertable list")
    void newFingerprintIsAlertable() {
        DetectedChange change = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");

        when(fingerprintRepo.findActiveByVendorAndHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(fingerprintRepo.findByVendorIdAndFingerprintHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(anyLong()))
                .thenReturn(List.of());
        when(fingerprintRepo.save(any(ChangeFingerprint.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<DetectedChange> alertable = fingerprintService.deduplicateAndFilter(
                VENDOR_ID, List.of(change), auditRun);

        assertThat(alertable).hasSize(1);
        assertThat(alertable.get(0).getFingerprintHash()).isNotNull();

        verify(fingerprintRepo, atLeastOnce()).save(fingerprintCaptor.capture());
        ChangeFingerprint saved = fingerprintCaptor.getAllValues().stream()
                .filter(fp -> fp.getChangeType() != null)
                .findFirst().orElseThrow();
        assertThat(saved.getIsActive()).isTrue();
        assertThat(saved.getChangeType()).isEqualTo("PARAM_REMOVED");
    }

    // ── ACTIVE fingerprints → suppressed ────────────────────────

    @Test
    @DisplayName("Already-active fingerprint → suppressed (not alertable)")
    void activeFingerprintIsSuppressed() {
        DetectedChange change = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");

        ChangeFingerprint existing = ChangeFingerprint.builder()
                .id(1L)
                .fingerprintHash("abc123")
                .isActive(true)
                .changeType("PARAM_REMOVED")
                .build();

        when(fingerprintRepo.findActiveByVendorAndHash(anyLong(), anyString()))
                .thenReturn(Optional.of(existing));
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(anyLong()))
                .thenReturn(List.of(existing));
        when(fingerprintRepo.save(any(ChangeFingerprint.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<DetectedChange> alertable = fingerprintService.deduplicateAndFilter(
                VENDOR_ID, List.of(change), auditRun);

        assertThat(alertable).isEmpty(); // suppressed
    }

    // ── RESOLVED → re-activated → alert ─────────────────────────

    @Test
    @DisplayName("Previously RESOLVED fingerprint reappears → re-activated and alertable")
    void resolvedFingerprintReappearsAndIsAlertable() {
        DetectedChange change = makeChange("GET", "/users", "PARAM_REMOVED", "#/params/id");

        ChangeFingerprint resolved = ChangeFingerprint.builder()
                .id(1L)
                .fingerprintHash("abc123")
                .isActive(false)
                .changeType("PARAM_REMOVED")
                .build();

        when(fingerprintRepo.findActiveByVendorAndHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());  // not active
        when(fingerprintRepo.findByVendorIdAndFingerprintHash(anyLong(), anyString()))
                .thenReturn(Optional.of(resolved)); // but exists (was resolved)
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(anyLong()))
                .thenReturn(List.of());
        when(fingerprintRepo.save(any(ChangeFingerprint.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<DetectedChange> alertable = fingerprintService.deduplicateAndFilter(
                VENDOR_ID, List.of(change), auditRun);

        assertThat(alertable).hasSize(1); // re-alerted!
    }

    // ── Auto-resolve ────────────────────────────────────────────

    @Test
    @DisplayName("Fingerprint no longer present → auto-resolved (isActive=false)")
    void staleFingerprintAutoResolved() {
        DetectedChange newChange = makeChange("GET", "/other", "NEW_ENDPOINT", "#/x");

        ChangeFingerprint oldActive = ChangeFingerprint.builder()
                .id(1L)
                .fingerprintHash("old-hash")
                .isActive(true)
                .changeType("PARAM_REMOVED")
                .build();

        when(fingerprintRepo.findActiveByVendorAndHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(fingerprintRepo.findByVendorIdAndFingerprintHash(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(anyLong()))
                .thenReturn(List.of(oldActive));  // oldActive not in current run
        when(fingerprintRepo.save(any(ChangeFingerprint.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        fingerprintService.deduplicateAndFilter(
                VENDOR_ID, List.of(newChange), auditRun);

        // oldActive should be resolved
        verify(fingerprintRepo, atLeastOnce()).save(fingerprintCaptor.capture());
        List<ChangeFingerprint> allSaved = fingerprintCaptor.getAllValues();
        ChangeFingerprint resolved = allSaved.stream()
                .filter(fp -> "old-hash".equals(fp.getFingerprintHash()))
                .findFirst().orElseThrow();
        assertThat(resolved.getIsActive()).isFalse(); // auto-resolved
    }

    // ── Empty changes ───────────────────────────────────────────

    @Test
    @DisplayName("Empty changes list returns empty alertable list")
    void emptyChangesReturnsEmpty() {
        when(fingerprintRepo.findByVendorIdAndIsActiveTrue(anyLong()))
                .thenReturn(List.of());

        List<DetectedChange> alertable = fingerprintService.deduplicateAndFilter(
                VENDOR_ID, List.of(), auditRun);

        assertThat(alertable).isEmpty();
    }

    // ── Helper ──────────────────────────────────────────────────

    private DetectedChange makeChange(String method, String path, String type, String pointer) {
        return DetectedChange.builder()
                .changeType(type)
                .severity(ChangeSeverity.HIGH)
                .direction("REQUEST")
                .httpMethod(method)
                .endpointPath(path)
                .jsonPointer(pointer)
                .description(type + " on " + path)
                .isBreaking(true)
                .build();
    }
}
