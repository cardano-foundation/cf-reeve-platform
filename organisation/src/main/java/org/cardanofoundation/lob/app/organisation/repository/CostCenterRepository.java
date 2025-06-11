package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;

public interface CostCenterRepository extends JpaRepository<OrganisationCostCenter, OrganisationCostCenter.Id> {

    @Query("SELECT t FROM OrganisationCostCenter t WHERE t.id.organisationId = :organisationId")
    Set<OrganisationCostCenter> findAllByOrganisationId(@Param("organisationId") String organisationId);

}
