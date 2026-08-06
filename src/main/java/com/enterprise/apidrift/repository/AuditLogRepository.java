package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.AuditLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    Page<AuditLogEntry> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String resourceType, Long resourceId, Pageable pageable);

    Page<AuditLogEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
