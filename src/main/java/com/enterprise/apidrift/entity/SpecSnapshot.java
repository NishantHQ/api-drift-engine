package com.enterprise.apidrift.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "spec_snapshots")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SpecSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private VendorConfig vendor;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "spec_version", length = 20)
    private String specVersion;

    @Column(name = "raw_spec", nullable = false, columnDefinition = "JSONB")
    private String rawSpec;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
