package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.repository.AccountEventRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class AccountEventService {

    private final AccountEventRepository accountEventRepository;
    private final ReferenceCodeRepository referenceCodeRepository;

    public Optional<AccountEvent> findById(String organisationId, String debitReferenceCode, String creditReferenceCode) {
        return accountEventRepository.findById(new AccountEvent.Id(organisationId, debitReferenceCode,creditReferenceCode));
    }

    public List<AccountEventView> getAllAccountEvent(String orgId) {
        return accountEventRepository.findAllByOrganisationId(orgId).stream()
                .map(AccountEventView::convertFromEntity
                )
                .toList();
    }

    @Transactional
    public Optional<AccountEventView> upsertAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate) {

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return Optional.empty();
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return Optional.empty();
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        AccountEvent accountEvent = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()).orElse(
                AccountEvent.builder()
                        .id(new AccountEvent.Id(orgId, debitReferenceG.getId().getReferenceCode(), creditReferenceG.getId().getReferenceCode()))
                        .customerCode(debitReferenceG.getId().getReferenceCode()+creditReferenceG.getId().getReferenceCode())
                        .build()
        );

        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setHierarchy(eventCodeUpdate.getHierarchy());
        accountEvent.setActive(eventCodeUpdate.getActive());
        accountEventRepository.save(accountEvent);

        return Optional.ofNullable(AccountEventView.convertFromEntity(accountEvent));
    }
}
