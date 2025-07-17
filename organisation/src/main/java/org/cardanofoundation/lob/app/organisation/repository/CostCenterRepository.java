package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

public interface CostCenterRepository extends JpaRepository<CostCenter, CostCenter.Id> {

    @Query("SELECT t FROM CostCenter t WHERE t.id.organisationId = :organisationId")
    Set<CostCenter> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT t FROM CostCenter t WHERE t.id = :Id AND t.active = :active AND (t.parent.id.customerCode IS NULL OR " +
            "t.parent.id.customerCode = (select t2.id.customerCode from CostCenter t2 WHERE t2.id.customerCode = t.parent.id.customerCode and t2.active = true))")
    Optional<CostCenter> findByIdAndActive(@Param("Id") CostCenter.Id Id, @Param("active") boolean active);

}
