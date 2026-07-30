package com.enterprise.apidrift.controller;

import com.enterprise.apidrift.dto.ChangeFeedResponse;
import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.repository.ChangeFingerprintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-vendor change feed — browse all detected changes across vendors
 * with filtering, pagination, and aggregate stats.
 * Endpoint: /api/v1/changes
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/changes")
@RequiredArgsConstructor
public class ChangeFeedController {

    private final ChangeFingerprintRepository fingerprintRepo;

    /**
     * Paginated, filterable feed of all changes across all vendors.
     * Query params: severity, vendorId, activeOnly, since (ISO-8601), page, size
     */
    @GetMapping
    public ResponseEntity<Page<ChangeFeedResponse>> listChanges(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /api/v1/changes — severity={}, vendorId={}, activeOnly={}, since={}, page={}, size={}",
                severity, vendorId, activeOnly, since, page, size);

        OffsetDateTime sinceDate = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceDate = OffsetDateTime.parse(since);
            } catch (Exception e) {
                log.warn("Invalid since date: {}", since);
            }
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "firstSeenAt"));
        Page<ChangeFingerprint> results = fingerprintRepo.findFiltered(
                vendorId, severity, activeOnly, sinceDate, pageable);

        Page<ChangeFeedResponse> response = results.map(this::toResponse);
        return ResponseEntity.ok(response);
    }

    /**
     * Aggregate counts by severity across all vendors.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        log.info("GET /api/v1/changes/stats");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalActiveChanges", (int) fingerprintRepo.findByIsActiveTrue().size());
        stats.put("vendorsWithActiveChanges", (int) fingerprintRepo.countDistinctVendorsWithActiveChanges());
        stats.put("bySeverity", fingerprintRepo.countActiveBySeverity().stream()
                .map(row -> Map.of("severity", row[0].toString(), "count", (Long) row[1]))
                .toList());
        return ResponseEntity.ok(stats);
    }

    private ChangeFeedResponse toResponse(ChangeFingerprint fp) {
        return ChangeFeedResponse.builder()
                .id(fp.getId())
                .vendorId(fp.getVendor().getId())
                .vendorName(fp.getVendor().getVendorName())
                .changeType(fp.getChangeType())
                .severity(fp.getSeverity().name())
                .httpMethod(fp.getHttpMethod())
                .endpointPath(fp.getEndpointPath())
                .jsonPointer(fp.getJsonPointer())
                .description(fp.getDescription())
                .fingerprintHash(fp.getFingerprintHash())
                .isActive(fp.getIsActive())
                .firstSeenAt(fp.getFirstSeenAt())
                .lastSeenAt(fp.getLastSeenAt())
                .build();
    }
}
