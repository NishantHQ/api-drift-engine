package com.enterprise.apidrift.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "vendor_configs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class VendorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vendor_name", nullable = false, unique = true, length = 100)
    private String vendorName;

    @Column(name = "spec_url", nullable = false, length = 2048)
    private String specUrl;

    @Column(name = "cron_expression", nullable = false, length = 50)
    @Builder.Default
    private String cronExpression = "0 0 * * * *";

    @Column(name = "auth_header_name", length = 100)
    private String authHeaderName;

    @Column(name = "encrypted_auth_token", columnDefinition = "TEXT")
    private String encryptedAuthToken;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Convert(converter = com.enterprise.apidrift.config.TagListConverter.class)
    @Column(name = "tags")
    @Builder.Default
    private List<String> tags = Collections.emptyList();

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
