package com.enterprise.apidrift.repository;

import com.enterprise.apidrift.entity.ServiceDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, Long> {

    List<ServiceDependency> findByVendorId(Long vendorId);

    List<ServiceDependency> findByServiceName(String serviceName);

    List<ServiceDependency> findByVendorIdAndServiceName(Long vendorId, String serviceName);

    /**
     * Find all unique service names that consume a given vendor.
     */
    @Query("SELECT DISTINCT sd.serviceName FROM ServiceDependency sd WHERE sd.vendor.id = :vendorId")
    Set<String> findDistinctServiceNamesByVendorId(@Param("vendorId") Long vendorId);

    /**
     * Find consuming services for a specific vendor endpoint + field combination.
     * Uses LIKE matching on endpointPath and jsonPointer for flexible lookup
     * (e.g., a registered dependency on /v1/charges matches a change on /v1/charges/{id}).
     */
    @Query("SELECT DISTINCT sd.serviceName FROM ServiceDependency sd " +
           "WHERE sd.vendor.id = :vendorId " +
           "AND (sd.endpointPath IS NULL OR :endpointPath LIKE CONCAT('%', sd.endpointPath, '%') " +
           "     OR sd.endpointPath LIKE CONCAT('%', :endpointPath, '%')) " +
           "AND (sd.jsonPointer IS NULL OR sd.jsonPointer = '' OR :jsonPointer LIKE CONCAT('%', sd.jsonPointer, '%') " +
           "     OR sd.jsonPointer LIKE CONCAT('%', :jsonPointer, '%'))")
    Set<String> findConsumers(@Param("vendorId") Long vendorId,
                              @Param("endpointPath") String endpointPath,
                              @Param("jsonPointer") String jsonPointer);

    boolean existsByVendorIdAndServiceNameAndEndpointPathAndHttpMethodAndJsonPointer(
            Long vendorId, String serviceName, String endpointPath, String httpMethod, String jsonPointer);
}
