package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            SELECT t FROM ChartOfAccount t
            WHERE t.Id.organisationId = :orgId
            AND (:customerCode IS NULL OR LOWER(t.id.customerCode) LIKE LOWER(CONCAT('%', CAST(:customerCode AS string), '%')))
            AND (:name IS NULL or LOWER(t.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
            AND (:currencies IS NULL OR LOWER(t.currencyId) IN :currencies)
            AND (:counterPartyIds IS NULL OR t.counterParty IN :counterPartyIds)
            AND (:types IS NULL OR (t.subType IS NOT NULL AND t.subType.type IS NOT NULL AND t.subType.type.id IN :types))
            AND (:subTypes IS NULL OR (t.subType IS NOT NULL AND t.subType.id IN :subTypes))
            AND (:referenceCodes IS NULL OR t.eventRefCode IN :referenceCodes)
            AND (:active IS NULL OR t.active = :active)
            """
    )
    Page<ChartOfAccount> findAllByOrganisationIdFiltered(@Param("orgId") String orgId, @Param("customerCode") String customerCode, @Param("name") String name, @Param("currencies") List<String> currencies, @Param("counterPartyIds") List<String> counterPartyIds, @Param("types") List<String> types, @Param("subTypes") List<String> subTypes, @Param("referenceCodes") List<String> referenceCodes, @Param("active") Boolean active, Pageable pageable);

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.Id.organisationId = :orgId AND t.Id.customerCode = :customerCode")
    Optional<ChartOfAccount> findAllByOrganisationIdAndReferenceCode(@Param("orgId") String orgId, @Param("customerCode") String customerCode);

    @Query("SELECT t FROM ChartOfAccount t " +
            "WHERE t.subType.id IN :subTypeIds")
    Set<ChartOfAccount> findAllByOrganisationIdSubTypeIds(@Param("subTypeIds") List<Long> mappingType);

    @Query("SELECT t FROM ChartOfAccount t WHERE t.id = :Id AND t.active = :active ")
    Optional<ChartOfAccount> findByIdAndActive(@Param("Id") ChartOfAccount.Id Id, @Param("active") boolean active);
}
