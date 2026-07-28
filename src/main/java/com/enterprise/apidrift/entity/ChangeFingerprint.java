package com.enterprise.apidrift.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "change_fingerprints")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ChangeFingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_run_id", nullable = false)
    private DiffAuditRun auditRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private VendorConfig vendor;

    @Column(name = "fingerprint_hash", nullable = false, length = 64)
    private String fingerprintHash;

    @Column(name = "change_type", nullable = false, length = 100)
    private String changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private ChangeSeverity severity;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "endpoint_path", length = 500)
    private String endpointPath;

    @Column(name = "json_pointer", length = 500)
    private String jsonPointer;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "first_seen_at")
    @Builder.Default
    private OffsetDateTime firstSeenAt = OffsetDateTime.now();

    @Column(name = "last_seen_at")
    @Builder.Default
    private OffsetDateTime lastSeenAt = OffsetDateTime.now();
}
