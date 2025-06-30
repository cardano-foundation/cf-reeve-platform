package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;

public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, ChartOfAccount.Id>{

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.subType.id = :subTypeId")
    Set<ChartOfAccount> findAllByOrganisationIdSubTypeId(@Param("subTypeId") Long subTypeId);

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.Id.organisationId = :orgId")
    Set<ChartOfAccount> findAllByOrganisationId(@Param("orgId") String orgId);

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.Id.organisationId = :orgId AND t.Id.customerCode = :customerCode")
    Optional<ChartOfAccount> findAllByOrganisationIdAndReferenceCode(@Param("orgId") String orgId, @Param("customerCode") String customerCode);

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.subType.id IN :subTypeIds")
    Set<ChartOfAccount> findAllByOrganisationIdSubTypeIds(@Param("subTypeIds") List<Long> mappingType);

    @Query("SELECT t FROM ChartOfAccount t WHERE t.id = :Id AND t.active = :active ")
    Optional<ChartOfAccount> findByIdAndActive(@Param("Id") ChartOfAccount.Id Id, @Param("active") boolean active);
}
