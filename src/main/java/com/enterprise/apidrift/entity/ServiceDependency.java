package com.enterprise.apidrift.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

/**
 * Tracks which internal services depend on which vendor API endpoints/fields.
 * Populated via the telemetry registration API by internal service teams
 * or automated service discovery tooling.
 *
 * Replaces the hardcoded mock data in TelemetryRegistry with a real,
 * queryable dependency graph.
 */
@Entity
@Table(name = "service_dependencies")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ServiceDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", nullable = false)
    private VendorConfig vendor;

    @Column(name = "endpoint_path", length = 500)
    private String endpointPath;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "json_pointer", length = 500)
    private String jsonPointer;

    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
