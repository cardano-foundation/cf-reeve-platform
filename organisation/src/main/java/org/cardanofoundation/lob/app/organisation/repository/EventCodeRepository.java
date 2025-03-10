package org.cardanofoundation.lob.app.organisation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.cardanofoundation.lob.app.organisation.domain.entity.EventCode;

public interface EventCodeRepository extends JpaRepository<EventCode, EventCode.Id> {

    @Query("SELECT rc FROM EventCode rc WHERE rc.id.organisationId = :orgId")
    List<EventCode> findAllByOrgId(String orgId);

    @Query("SELECT rc FROM EventCode rc WHERE rc.id.organisationId = :orgId AND rc.id.debitReferenceCode = :debitReferenceCode AND rc.id.creditReferenceCode = :creditReferenceCode")
    Optional<EventCode> findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(String orgId, String debitReferenceCode,String creditReferenceCode);
}
