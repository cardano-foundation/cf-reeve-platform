package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountEvent;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.EventCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountEventView;
import org.cardanofoundation.lob.app.organisation.repository.AccountEventRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class AccountEventService {

    private final AccountEventRepository accountEventRepository;
    private final ReferenceCodeRepository referenceCodeRepository;
    private final OrganisationService organisationService;
    private final CsvParser<EventCodeUpdate> csvParser;

    public Optional<AccountEvent> findByIdAndActive(String organisationId, String debitReferenceCode, String creditReferenceCode) {
        return accountEventRepository.findByIdAndActive(new AccountEvent.Id(organisationId, debitReferenceCode, creditReferenceCode),true );
    }

    public List<AccountEventView> getAllAccountEvent(String orgId) {
        return accountEventRepository.findAllByOrganisationId(orgId).stream()
                .map(AccountEventView::convertFromEntity
                )
                .toList();
    }

    @Transactional
    public AccountEventView insertAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate, boolean isUpsert) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find refernce code by Id: %s:%s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find refernce code by Id: %s:%s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        // If the account event already exists and we are not upserting, return an error
        if (accountEventOpt.isPresent() && !isUpsert) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ACCOUNT_EVENT_ALREADY_EXISTS")
                    .withDetail("Account event already exists for debit reference code: %s and credit reference code: %s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.CONFLICT)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }

        AccountEvent accountEvent = AccountEvent.builder()
                .id(new AccountEvent.Id(orgId, debitReferenceG.getId().getReferenceCode(), creditReferenceG.getId().getReferenceCode()))
                .customerCode(debitReferenceG.getId().getReferenceCode() + creditReferenceG.getId().getReferenceCode())
                .build();

        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(eventCodeUpdate.getActive());

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Transactional
    public AccountEventView updateAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find refernce code by Id: %s and %s:%s".formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find refernce code by Id: %s and %s:%s".formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }

        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        if (accountEventOpt.isEmpty()){
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ACCOUNT_EVENT_NOT_FOUND")
                    .withDetail("Account event not found for debit reference code: %s and credit reference code: %s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        }
        AccountEvent accountEvent = accountEventOpt.get();
        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(eventCodeUpdate.getActive());

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Transactional
    public Either<Set<Problem>, Set<AccountEventView>> insertAccountEventByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, EventCodeUpdate.class).fold(
                problem ->
                        Either.left(Set.of(problem)),
                eventCodeUpdates ->
                        Either.right(eventCodeUpdates.stream().map(eventCodeUpdate ->  insertAccountEvent(orgId, eventCodeUpdate, true)).collect(Collectors.toSet()))
        );
    }
}
