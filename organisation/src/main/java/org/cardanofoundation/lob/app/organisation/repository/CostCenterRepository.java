package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

public interface CostCenterRepository extends JpaRepository<CostCenter, CostCenter.Id> {

    @Query("""
            SELECT t FROM CostCenter t LEFT JOIN FETCH t.parent
            WHERE t.id.organisationId = :organisationId
            AND (:customerCode IS NULL OR t.id.customerCode LIKE %:customerCode%)
            AND (:name IS NULL OR t.name LIKE %:name%)
            AND (:parentCustomerCodes IS NULL OR t.parentCustomerCode IN :parentCustomerCodes)
            AND (:active IS NULL OR t.active = :active)
            """)
    Page<CostCenter> findAllByOrganisationId(@Param("organisationId") String id, @Param("customerCode") String customerCode, @Param("name") String name, @Param("parentCustomerCodes") List<String> parentCustomerCodes, @Param("active") boolean active, Pageable pageable);

    @Query("SELECT t FROM CostCenter t WHERE t.id = :Id AND t.active = :active ")
    Optional<CostCenter> findByIdAndActive(@Param("Id") CostCenter.Id Id, @Param("active") boolean active);

}
