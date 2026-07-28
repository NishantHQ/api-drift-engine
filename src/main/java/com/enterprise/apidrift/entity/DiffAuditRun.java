package com.enterprise.apidrift.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "diff_audit_runs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DiffAuditRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private VendorConfig vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_snapshot_id")
    private SpecSnapshot oldSnapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_snapshot_id")
    private SpecSnapshot newSnapshot;

    @Column(name = "total_changes", nullable = false)
    @Builder.Default
    private Integer totalChanges = 0;

    @Column(name = "breaking_changes", nullable = false)
    @Builder.Default
    private Integer breakingChanges = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private RunStatus status = RunStatus.SUCCESS;

    @Column(name = "executed_at")
    @Builder.Default
    private OffsetDateTime executedAt = OffsetDateTime.now();
}
