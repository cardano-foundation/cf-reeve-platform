package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;

public interface ChartOfAccountRepository extends JpaRepository<OrganisationChartOfAccount, OrganisationChartOfAccount.Id>{

    @Query("SELECT t FROM OrganisationChartOfAccount t " +
            "WHERE t.subType.id = :subTypeId")
    Set<OrganisationChartOfAccount> findAllByOrganisationIdSubTypeId(@Param("subTypeId") Long subTypeId);

    @Query("SELECT t FROM OrganisationChartOfAccount t " +
            "WHERE t.Id.organisationId = :orgId")
    Set<OrganisationChartOfAccount> findAllByOrganisationId(@Param("orgId") String orgId);

    @Query("SELECT t FROM OrganisationChartOfAccount t " +
            "WHERE t.Id.organisationId = :orgId AND t.Id.customerCode = :customerCode")
    Optional<OrganisationChartOfAccount> findAllByOrganisationIdAndReferenceCode(@Param("orgId") String orgId, @Param("customerCode") String customerCode);

    @Query("SELECT t FROM OrganisationChartOfAccount t " +
            "WHERE t.subType.id IN :subTypeIds")
    Set<OrganisationChartOfAccount> findAllByOrganisationIdSubTypeIds(@Param("subTypeIds") List<Long> mappingType);
}
