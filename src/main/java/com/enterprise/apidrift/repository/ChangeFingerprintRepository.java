package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.ChangeFingerprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
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
}
