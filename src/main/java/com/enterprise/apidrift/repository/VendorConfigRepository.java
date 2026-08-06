package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.VendorConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendorConfigRepository extends JpaRepository<VendorConfig, Long> {

    Optional<VendorConfig> findByVendorName(String vendorName);

    List<VendorConfig> findByIsActiveTrue();

    boolean existsByVendorName(String vendorName);

    @Query("SELECT v FROM VendorConfig v WHERE CONCAT(',', v.tags, ',') LIKE CONCAT('%,', :tag, ',%')")
    List<VendorConfig> findByTag(@Param("tag") String tag);

    @Query("SELECT v FROM VendorConfig v WHERE CONCAT(',', v.tags, ',') LIKE CONCAT('%,', :tag, ',%')")
    Page<VendorConfig> findByTag(@Param("tag") String tag, Pageable pageable);
}
