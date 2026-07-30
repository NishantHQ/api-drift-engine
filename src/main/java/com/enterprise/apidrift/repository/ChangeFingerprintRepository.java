package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.ChangeFingerprint;
import com.enterprise.apidrift.entity.ChangeSeverity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChangeFingerprintRepository extends JpaRepository<ChangeFingerprint, Long> {

    @Query("SELECT c FROM ChangeFingerprint c WHERE c.vendor.id = :vendorId AND c.fingerprintHash = :hash AND c.isActive = true")
    Optional<ChangeFingerprint> findActiveByVendorAndHash(@Param("vendorId") Long vendorId, @Param("hash") String hash);

    List<ChangeFingerprint> findByVendorIdAndIsActiveTrue(Long vendorId);

    List<ChangeFingerprint> findByIsActiveTrue();

    List<ChangeFingerprint> findByAuditRunId(Long auditRunId);

    Optional<ChangeFingerprint> findByVendorIdAndFingerprintHash(Long vendorId, String fingerprintHash);

    @Query("SELECT c.severity, COUNT(c) FROM ChangeFingerprint c WHERE c.isActive = true GROUP BY c.severity")
    List<Object[]> countActiveBySeverity();

    @Query("SELECT COUNT(DISTINCT c.vendor.id) FROM ChangeFingerprint c WHERE c.isActive = true")
    long countDistinctVendorsWithActiveChanges();

    long countByIsActiveTrue();

    @Query("SELECT c FROM ChangeFingerprint c WHERE "
            + "(:vendorId IS NULL OR c.vendor.id = :vendorId) "
            + "AND (:severity IS NULL OR c.severity = :severity) "
            + "AND (:activeOnly = false OR c.isActive = true) "
            + "AND (:since IS NULL OR c.firstSeenAt >= :since)")
    Page<ChangeFingerprint> findFiltered(@Param("vendorId") Long vendorId,
                                         @Param("severity") ChangeSeverity severity,
                                         @Param("activeOnly") boolean activeOnly,
                                         @Param("since") OffsetDateTime since,
                                         Pageable pageable);

    List<ChangeFingerprint> findByVendorIdAndFirstSeenAtAfterOrderByFirstSeenAtDesc(
            Long vendorId, OffsetDateTime since);
}
