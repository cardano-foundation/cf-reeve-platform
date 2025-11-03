package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;

public interface AccountEventRepository extends JpaRepository<AccountEvent, AccountEvent.Id> {

    @Query("""
            SELECT a FROM AccountEvent a
            WHERE a.id.organisationId = :organisationId
            AND (:customerCode IS NULL OR LOWER(a.customerCode) LIKE LOWER(CONCAT('%', CAST(:customerCode AS string), '%')))
            AND (:name IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
            AND (:creditRefCodes IS NULL OR a.id.creditReferenceCode IN :creditRefCodes)
            AND (:debitRefCodes IS NULL OR a.id.debitReferenceCode IN :debitRefCodes)
            AND (:active IS NULL OR a.active = :active)
            """)
    Page<AccountEvent> findAllByOrganisationId(@Param("organisationId") String organisationId, @Param("customerCode") String customerCode, @Param("name") String name, @Param("creditRefCodes") List<String> creditRefCodes, @Param("debitRefCodes") List<String> debitRefCodes, @Param("active") Boolean active, Pageable pageable);

    @Query("""
            SELECT a FROM AccountEvent a
            WHERE a.id.organisationId = :organisationId
            """)
    Set<AccountEvent> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT rc FROM AccountEvent rc WHERE rc.id.organisationId = :orgId AND rc.id.debitReferenceCode = :debitReferenceCode AND rc.id.creditReferenceCode = :creditReferenceCode")
    Optional<AccountEvent> findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(@Param("orgId") String orgId, @Param("debitReferenceCode") String debitReferenceCode, @Param("creditReferenceCode") String creditReferenceCode);

    @Query("SELECT av FROM AccountEvent av WHERE av.id = :Id AND av.active = :active AND av.Id.debitReferenceCode = (SELECT rc.Id.referenceCode FROM ReferenceCode rc WHERE rc.isActive = true AND rc.Id.referenceCode = av.Id.debitReferenceCode) AND av.Id.creditReferenceCode = (SELECT rc.Id.referenceCode FROM ReferenceCode rc WHERE rc.isActive = true AND  rc.Id.referenceCode = av.Id.creditReferenceCode)")
    Optional<AccountEvent> findByIdAndActive(@Param("Id") AccountEvent.Id Id, @Param("active") boolean active);

    @Query("SELECT rc FROM AccountEvent rc WHERE rc.id.organisationId = :orgId AND (rc.id.debitReferenceCode = :referenceCode OR rc.id.creditReferenceCode = :referenceCode)")
    List<AccountEvent> findByOrgIdAndRefCodeAccount(@Param("orgId") String orgId, @Param("referenceCode") String referenceCode);

}
