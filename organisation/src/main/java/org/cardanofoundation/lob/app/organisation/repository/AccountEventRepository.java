package org.cardanofoundation.lob.app.organisation.repository;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;

public interface AccountEventRepository extends JpaRepository<AccountEvent, AccountEvent.Id>{

    @Query("SELECT a FROM AccountEvent a " +
            "WHERE a.id.organisationId = :organisationId")
    Set<AccountEvent> findAllByOrganisationId(@Param("organisationId") String organisationId);

    @Query("SELECT rc FROM AccountEvent rc WHERE rc.id.organisationId = :orgId AND rc.id.debitReferenceCode = :debitReferenceCode AND rc.id.creditReferenceCode = :creditReferenceCode")
    Optional<AccountEvent> findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(@Param("orgId") String orgId, @Param("debitReferenceCode") String debitReferenceCode, @Param("creditReferenceCode") String creditReferenceCode);

}
