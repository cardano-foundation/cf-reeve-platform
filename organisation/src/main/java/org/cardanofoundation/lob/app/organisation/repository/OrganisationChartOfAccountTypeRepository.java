package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountType;

public interface OrganisationChartOfAccountTypeRepository extends JpaRepository<OrganisationChartOfAccountType, String> {

    @Query("SELECT t FROM OrganisationChartOfAccountType t " +
            "LEFT JOIN FETCH t.subTypes tst " +
            "WHERE t.organisationId = :organisationId")
    Set<OrganisationChartOfAccountType> findAllByOrganisationId(@Param("organisationId") String organisationId);

    Optional<OrganisationChartOfAccountType> findFirstByOrganisationIdAndName(String organisationId, String name);

    @Query("SELECT MAX(t.id) FROM OrganisationChartOfAccountType t")
    Long getMaxId();
}
