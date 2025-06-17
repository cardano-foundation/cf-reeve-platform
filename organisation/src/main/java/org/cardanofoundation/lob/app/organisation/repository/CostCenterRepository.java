package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

public interface CostCenterRepository extends JpaRepository<CostCenter, CostCenter.Id> {

    @Query("SELECT t FROM CostCenter t WHERE t.id.organisationId = :organisationId")
    Set<CostCenter> findAllByOrganisationId(@Param("organisationId") String organisationId);

}
