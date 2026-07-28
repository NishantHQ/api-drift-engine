package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.VendorConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendorConfigRepository extends JpaRepository<VendorConfig, Long> {

    Optional<VendorConfig> findByVendorName(String vendorName);

    List<VendorConfig> findByIsActiveTrue();

    boolean existsByVendorName(String vendorName);
}
