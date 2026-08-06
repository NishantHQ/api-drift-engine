package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.SpecSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpecSnapshotRepository extends JpaRepository<SpecSnapshot, Long> {

    List<SpecSnapshot> findByVendorIdOrderByCreatedAtDesc(Long vendorId);

    Page<SpecSnapshot> findByVendorIdOrderByCreatedAtDesc(Long vendorId, Pageable pageable);

    @Query("SELECT s FROM SpecSnapshot s WHERE s.vendor.id = :vendorId ORDER BY s.createdAt DESC LIMIT 1")
    Optional<SpecSnapshot> findLatestByVendorId(@Param("vendorId") Long vendorId);

    @Query("SELECT s FROM SpecSnapshot s WHERE s.vendor.id = :vendorId AND s.contentHash = :hash")
    Optional<SpecSnapshot> findByVendorIdAndHash(@Param("vendorId") Long vendorId, @Param("hash") String hash);
}
