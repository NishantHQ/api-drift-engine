package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.DiffAuditRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface DiffAuditRunRepository extends JpaRepository<DiffAuditRun, Long> {

    List<DiffAuditRun> findByVendorIdOrderByExecutedAtDesc(Long vendorId);

    List<DiffAuditRun> findTop10ByOrderByExecutedAtDesc();

    long countByVendorId(Long vendorId);

    List<DiffAuditRun> findByVendorIdAndExecutedAtAfterOrderByExecutedAtDesc(
            Long vendorId, OffsetDateTime since);
}
