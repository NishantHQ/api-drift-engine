package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.DetectedChange;
import com.enterprise.apidrift.dto.DiffTriggerResponse;
import com.enterprise.apidrift.dto.ResolveRequest;
import com.enterprise.apidrift.dto.VendorStatsResponse;
import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.entity.DiffAuditRun;
import com.enterprise.apidrift.entity.RunStatus;
import com.enterprise.apidrift.entity.VendorConfig;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import com.enterprise.apidrift.repository.DiffAuditRunRepository;
import com.enterprise.apidrift.repository.VendorConfigRepository;
import com.enterprise.apidrift.service.AuditLogService;
import com.enterprise.apidrift.service.IngestionOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for diff operations and audit history.
 * Endpoint: /api/v1/diffs
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/diffs")
@RequiredArgsConstructor
public class DiffController {

    private final IngestionOrchestrator orchestrator;
    private final VendorConfigRepository vendorRepo;
    private final DiffAuditRunRepository auditRepo;
    private final ChangeFingerprintRepository fingerprintRepo;
    private final AuditLogService auditLogService;

    /**
     * Manually trigger a diff run for a specific vendor.
     */
    @PostMapping("/trigger/{vendorId}")
    public ResponseEntity<DiffTriggerResponse> triggerDiff(@PathVariable Long vendorId,
                                                            HttpServletRequest httpRequest) {
        log.info("POST /api/v1/diffs/trigger/{} — manual diff triggered", vendorId);
        VendorConfig vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null) {
            log.warn("Trigger failed: vendor id={} not found", vendorId);
            return ResponseEntity.notFound().build();
        }

        log.info("Manual diff triggered for vendor: {}", vendor.getVendorName());
        orchestrator.runPipelineAsync(vendor);
        auditLogService.log(httpRequest.getRemoteUser(), "DIFF_TRIGGERED", "vendor",
                vendorId, "Manual diff triggered for " + vendor.getVendorName(),
                httpRequest.getRemoteAddr());

        return ResponseEntity.accepted().body(DiffTriggerResponse.builder()
                .vendorName(vendor.getVendorName())
                .status(RunStatus.IN_PROGRESS.name())
                .totalChanges(0)
                .breakingChanges(0)
                .executedAt(OffsetDateTime.now())
                .build());
    }

    /**
     * Get audit run history for a vendor.
     */
    @GetMapping("/history/{vendorId}")
    public ResponseEntity<Page<DiffTriggerResponse>> getHistory(
            @PathVariable Long vendorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/v1/diffs/history/{} — page={}, size={}", vendorId, page, size);
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "executedAt"));
        Page<DiffTriggerResponse> history = auditRepo
                .findByVendorIdOrderByExecutedAtDesc(vendorId, pageable)
                .map(run -> DiffTriggerResponse.builder()
                        .auditRunId(run.getId())
                        .vendorName(run.getVendor().getVendorName())
                        .status(run.getStatus().name())
                        .totalChanges(run.getTotalChanges())
                        .breakingChanges(run.getBreakingChanges())
                        .executedAt(run.getExecutedAt())
                        .build());

        return ResponseEntity.ok(history);
    }

    /**
     * Get active (unresolved) changes for a vendor.
     */
    @GetMapping("/active/{vendorId}")
    public ResponseEntity<List<DetectedChange>> getActiveChanges(@PathVariable Long vendorId) {
        log.info("GET /api/v1/diffs/active/{}", vendorId);
        if (!vendorRepo.existsById(vendorId)) {
            return ResponseEntity.notFound().build();
        }

        List<DetectedChange> active = fingerprintRepo.findByVendorIdAndIsActiveTrue(vendorId)
                .stream()
                .map(fp -> DetectedChange.builder()
                        .changeType(fp.getChangeType())
                        .severity(fp.getSeverity())
                        .direction(fp.getChangeType().contains("REQUEST") ? "REQUEST"
                                : fp.getChangeType().contains("RESPONSE") ? "RESPONSE" : "WEBHOOK")
                        .httpMethod(fp.getHttpMethod())
                        .endpointPath(fp.getEndpointPath())
                        .jsonPointer(fp.getJsonPointer())
                        .description(fp.getDescription())
                        .fingerprintHash(fp.getFingerprintHash())
                        .isBreaking(true)
                        .build())
                .toList();

        return ResponseEntity.ok(active);
    }

    /**
     * Manually resolve a change fingerprint.
     * Teams use this to acknowledge they've handled a breaking change.
     * The fingerprint is marked inactive and will not re-alert unless
     * the change reappears in a future diff run.
     */
    @PostMapping("/resolve/{fingerprintId}")
    public ResponseEntity<DetectedChange> resolveChange(@PathVariable Long fingerprintId,
                                                         @RequestBody(required = false) ResolveRequest request) {
        log.info("POST /api/v1/diffs/resolve/{} — manual resolution", fingerprintId);

        ChangeFingerprint fingerprint = fingerprintRepo.findById(fingerprintId).orElse(null);
        if (fingerprint == null) {
            log.warn("Resolution failed: fingerprint id={} not found", fingerprintId);
            return ResponseEntity.notFound().build();
        }

        fingerprint.setIsActive(false);
        fingerprint.setResolvedBy(request != null ? request.getResolvedBy() : null);
        fingerprint.setResolutionNotes(request != null ? request.getResolutionNotes() : null);
        fingerprint.setResolvedAt(OffsetDateTime.now());
        fingerprintRepo.save(fingerprint);

        log.info("Fingerprint {} resolved by {} — {}",
                fingerprint.getFingerprintHash(),
                fingerprint.getResolvedBy() != null ? fingerprint.getResolvedBy() : "unknown",
                fingerprint.getResolutionNotes() != null ? fingerprint.getResolutionNotes() : "no notes");

        return ResponseEntity.ok(DetectedChange.builder()
                .changeType(fingerprint.getChangeType())
                .severity(fingerprint.getSeverity())
                .httpMethod(fingerprint.getHttpMethod())
                .endpointPath(fingerprint.getEndpointPath())
                .jsonPointer(fingerprint.getJsonPointer())
                .description(fingerprint.getDescription())
                .fingerprintHash(fingerprint.getFingerprintHash())
                .isBreaking(true)
                .build());
    }

    /**
     * Get per-month change statistics and trends for a vendor.
     * Groups audit runs and changes by month for the last N months.
     */
    @GetMapping("/stats/{vendorId}")
    public ResponseEntity<VendorStatsResponse> getStats(@PathVariable Long vendorId,
                                                        @RequestParam(defaultValue = "6") int months) {
        log.info("GET /api/v1/diffs/stats/{} — months={}", vendorId, months);

        VendorConfig vendor = vendorRepo.findById(vendorId).orElse(null);
        if (vendor == null) {
            return ResponseEntity.notFound().build();
        }

        OffsetDateTime since = OffsetDateTime.now().minusMonths(months);

        // Fetch raw data
        List<DiffAuditRun> runs = auditRepo.findByVendorIdAndExecutedAtAfterOrderByExecutedAtDesc(
                vendorId, since);
        List<ChangeFingerprint> fingerprints = fingerprintRepo
                .findByVendorIdAndFirstSeenAtAfterOrderByFirstSeenAtDesc(vendorId, since);

        // Group by month in Java (avoids DB-specific TO_CHAR, works with H2 tests)
        Map<YearMonth, List<DiffAuditRun>> runsByMonth = runs.stream()
                .collect(Collectors.groupingBy(r -> YearMonth.from(r.getExecutedAt())));
        Map<YearMonth, List<ChangeFingerprint>> changesByMonth = fingerprints.stream()
                .collect(Collectors.groupingBy(c -> YearMonth.from(c.getFirstSeenAt())));

        // Build sorted month keys (most recent first)
        TreeSet<YearMonth> allMonths = new TreeSet<>(Comparator.reverseOrder());
        allMonths.addAll(runsByMonth.keySet());
        allMonths.addAll(changesByMonth.keySet());

        List<VendorStatsResponse.MonthlyStats> monthly = new ArrayList<>();
        long totalRuns = 0, totalChanges = 0, totalBreaking = 0;

        for (YearMonth ym : allMonths) {
            List<DiffAuditRun> monthRuns = runsByMonth.getOrDefault(ym, List.of());
            List<ChangeFingerprint> monthChanges = changesByMonth.getOrDefault(ym, List.of());

            long breaking = monthChanges.stream()
                    .filter(c -> c.getSeverity().name().equals("CRITICAL")
                            || c.getSeverity().name().equals("HIGH"))
                    .count();
            long resolved = monthChanges.stream().filter(c -> !c.getIsActive()).count();
            long unresolved = monthChanges.size() - resolved;

            monthly.add(VendorStatsResponse.MonthlyStats.builder()
                    .yearMonth(ym.toString())
                    .auditRuns(monthRuns.size())
                    .changesDetected(monthChanges.size())
                    .breakingChanges(breaking)
                    .resolved(resolved)
                    .unresolved(unresolved)
                    .build());

            totalRuns += monthRuns.size();
            totalChanges += monthChanges.size();
            totalBreaking += breaking;
        }

        long activeNow = fingerprintRepo.findByVendorIdAndIsActiveTrue(vendorId).size();

        return ResponseEntity.ok(VendorStatsResponse.builder()
                .vendorId(vendorId)
                .vendorName(vendor.getVendorName())
                .totalAuditRuns(totalRuns)
                .totalChangesDetected(totalChanges)
                .totalBreakingChanges(totalBreaking)
                .activeChanges(activeNow)
                .monthlyBreakdown(monthly)
                .build());
    }
}
