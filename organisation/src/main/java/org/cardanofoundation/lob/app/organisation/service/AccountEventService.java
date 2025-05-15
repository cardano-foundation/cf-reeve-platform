package org.cardanofoundation.lob.app.organisation.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    public Optional<AccountEvent> findById(String organisationId, String debitReferenceCode, String creditReferenceCode) {
        return accountEventRepository.findById(new AccountEvent.Id(organisationId, debitReferenceCode, creditReferenceCode));
    }

    public List<AccountEventView> getAllAccountEvent(String orgId) {
        return accountEventRepository.findAllByOrganisationId(orgId).stream()
                .map(AccountEventView::convertFromEntity
                )
                .toList();
    }

    @Transactional
    public AccountEventView insertAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build());

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        if (accountEventOpt.isPresent()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ACCOUNT_EVENT_ALREADY_EXISTS")
                    .withDetail(STR."Account event already exists for debit reference code: \{eventCodeUpdate.getDebitReferenceCode()} and credit reference code: \{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.CONFLICT)
                    .build());
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
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build());

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        if (accountEventOpt.isEmpty()){
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ACCOUNT_EVENT_NOT_FOUND")
                    .withDetail(STR."Account event not found for debit reference code: \{eventCodeUpdate.getDebitReferenceCode()} and credit reference code: \{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }
        AccountEvent accountEvent = accountEventOpt.get();
        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(eventCodeUpdate.getActive());

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Deprecated
    @Transactional
    public AccountEventView upsertAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build());

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail(STR."Unable to find refernce code by Id: \{orgId} and \{eventCodeUpdate.getDebitReferenceCode()}:\{eventCodeUpdate.getCreditReferenceCode()}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        AccountEvent accountEvent = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()).orElse(
                AccountEvent.builder()
                        .id(new AccountEvent.Id(orgId, debitReferenceG.getId().getReferenceCode(), creditReferenceG.getId().getReferenceCode()))
                        .customerCode(debitReferenceG.getId().getReferenceCode() + creditReferenceG.getId().getReferenceCode())
                        .build()
        );

        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(eventCodeUpdate.getActive());

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    public Either<Set<Problem>, Set<AccountEventView>> insertAccountEventByCsv(String orgId, MultipartFile file) {

        Either<Problem, List<EventCodeUpdate>> lists = csvParser.parseCsv(file, EventCodeUpdate.class);
        if (lists.isLeft()) {
            return Either.left(Set.of(lists.getLeft()));
        }

        List<EventCodeUpdate> eventCodeUpdates = lists.get();
        Set<AccountEventView> accountEventViews = new HashSet<>();
        Set<Problem> errors = new HashSet<>();
        for (EventCodeUpdate eventCodeUpdate : eventCodeUpdates) {
            AccountEventView accountEventView = insertAccountEvent(orgId, eventCodeUpdate);
            if (accountEventView.getError().isPresent()) {
                Problem error = accountEventView.getError().get();
                errors.add(error);
            } else {
                accountEventViews.add(accountEventView);
            }
        }
        if(!errors.isEmpty()) {
            return Either.left(errors);
        }
        return Either.right(accountEventViews);
    }
}
