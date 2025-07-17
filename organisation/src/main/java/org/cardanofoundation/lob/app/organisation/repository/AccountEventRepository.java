package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;

public interface AccountEventRepository extends JpaRepository<AccountEvent, AccountEvent.Id> {

    @Query("SELECT a FROM AccountEvent a " +
            "WHERE a.id.organisationId = :organisationId")
    Set<AccountEvent> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT rc FROM AccountEvent rc WHERE rc.id.organisationId = :orgId AND rc.id.debitReferenceCode = :debitReferenceCode AND rc.id.creditReferenceCode = :creditReferenceCode")
    Optional<AccountEvent> findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(@Param("orgId") String orgId, @Param("debitReferenceCode") String debitReferenceCode, @Param("creditReferenceCode") String creditReferenceCode);

    @Query("SELECT av FROM AccountEvent av WHERE av.id = :Id AND av.active = :active AND " +
            "av.Id.debitReferenceCode = (SELECT rc.Id.referenceCode FROM ReferenceCode rc WHERE rc.isActive = true AND rc.Id.referenceCode = av.Id.debitReferenceCode AND (rc.parentReferenceCode IS NULL OR rc.parentReferenceCode = (SELECT rcp.Id.referenceCode FROM ReferenceCode rcp WHERE rcp.isActive = true AND rcp.Id.referenceCode = rc.parentReferenceCode))) AND " +
            "av.Id.creditReferenceCode =(SELECT rc.Id.referenceCode FROM ReferenceCode rc WHERE rc.isActive = true AND rc.Id.referenceCode = av.Id.creditReferenceCode AND (rc.parentReferenceCode IS NULL OR rc.parentReferenceCode = (SELECT rcp.Id.referenceCode FROM ReferenceCode rcp WHERE rcp.isActive = true AND rcp.Id.referenceCode = rc.parentReferenceCode)))")
    Optional<AccountEvent> findByIdAndActive(@Param("Id") AccountEvent.Id Id, @Param("active") boolean active);

    @Query("SELECT rc FROM AccountEvent rc WHERE rc.id.organisationId = :orgId AND (rc.id.debitReferenceCode = :referenceCode OR rc.id.creditReferenceCode = :referenceCode)")
    List<AccountEvent> findByOrgIdAndRefCodeAccount(@Param("orgId") String orgId, @Param("referenceCode") String referenceCode);



}
