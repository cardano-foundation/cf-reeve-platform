package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;

public interface CostCenterRepository extends JpaRepository<CostCenter, CostCenter.Id> {

    @Query("SELECT DISTINCT t FROM CostCenter t LEFT JOIN FETCH t.parent LEFT JOIN FETCH t.children WHERE t.id.organisationId = :organisationId")
    Set<CostCenter> findAllByOrganisationIdWithParentAndChildren(@Param("organisationId") String organisationId);

    @Query("SELECT t FROM CostCenter t WHERE t.id = :Id AND t.active = :active ")
    Optional<CostCenter> findByIdAndActive(@Param("Id") CostCenter.Id Id, @Param("active") boolean active);

}
