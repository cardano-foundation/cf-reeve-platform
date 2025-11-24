package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.ACCOUNT_EVENT_MAPPINGS;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
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
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class AccountEventService {

    private final AccountEventRepository accountEventRepository;
    private final ReferenceCodeRepository referenceCodeRepository;
    private final OrganisationService organisationService;
    private final CsvParser<EventCodeUpdate> csvParser;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Optional<AccountEvent> findByIdAndActive(String organisationId, String debitReferenceCode, String creditReferenceCode) {
        return accountEventRepository.findByIdAndActive(new AccountEvent.Id(organisationId, debitReferenceCode, creditReferenceCode), true);
    }

    public Either<Problem, List<AccountEventView>> getAllAccountEvent(String orgId, String customerCode, String name, List<String> creditRefCodes, List<String> debitRefCodes, Boolean active, Pageable pageable) {
        Either<Problem, Pageable> pageables = jpaSortFieldValidator.validateEntity(AccountEvent.class, pageable, ACCOUNT_EVENT_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        return Either.right(accountEventRepository.findAllByOrganisationId(orgId, customerCode, name, creditRefCodes, debitRefCodes, active, pageable).stream()
                .map(AccountEventView::convertFromEntity
                )
                .toList());
    }

    @Transactional
    public AccountEventView insertAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate, boolean isUpsert) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_REFERNCE_CODE_BY_ID_S_S.formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_REFERNCE_CODE_BY_ID_S_S.formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        // If the account event already exists and we are not upserting, return an error
        if (accountEventOpt.isPresent() && !isUpsert) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ACCOUNT_EVENT_ALREADY_EXISTS)
                    .withDetail("Account event already exists for debit reference code: %s and credit reference code: %s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.CONFLICT)
                    .build(), eventCodeUpdate);
        }

        AccountEvent accountEvent = AccountEvent.builder()
                .id(new AccountEvent.Id(orgId, Objects.requireNonNull(debitReferenceG.getId()).getReferenceCode(), creditReferenceG.getId().getReferenceCode()))
                .customerCode(debitReferenceG.getId().getReferenceCode() + creditReferenceG.getId().getReferenceCode())
                .build();

        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(isActive(debitReference.get(), creditReference.get()));

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Transactional
    public AccountEventView updateAccountEvent(String orgId, EventCodeUpdate eventCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);

        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_REFERENCE_CODE_BY_ID_S_AND_S_S.formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND)
                    .withDetail(ErrorTitleConstants.UNABLE_TO_FIND_REFERENCE_CODE_BY_ID_S_AND_S_S.formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);
        }

        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        if (accountEventOpt.isEmpty()){
            return AccountEventView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ACCOUNT_EVENT_NOT_FOUND)
                    .withDetail("Account event not found for debit reference code: %s and credit reference code: %s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), eventCodeUpdate);
        }
        AccountEvent accountEvent = accountEventOpt.get();
        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(isActive(debitReference.get(), creditReference.get()));

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Transactional
    public Either<List<Problem>, List<AccountEventView>> insertAccountEventByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, EventCodeUpdate.class).fold(
                problem ->
                        Either.left(List.of(problem)),
                eventCodeUpdates ->
                        Either.right(eventCodeUpdates.stream().map(eventCodeUpdate -> {
                            Errors errors = validator.validateObject(eventCodeUpdate);
                            List<ObjectError> allErrors = errors.getAllErrors();
                            if (!allErrors.isEmpty()) {
                                return AccountEventView.createFail(Problem.builder()
                                        .withTitle(ErrorTitleConstants.VALIDATION_ERROR)
                                        .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                                        .withStatus(Status.BAD_REQUEST)
                                        .build(), eventCodeUpdate);
                            }
                             return insertAccountEvent(orgId, eventCodeUpdate, true);
                        }).toList())
        );
    }

    public void updateStatus(String orgId, String refCode) {
        accountEventRepository.findByOrgIdAndRefCodeAccount(orgId, refCode).forEach(accountEvent -> {

            ReferenceCode debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, accountEvent.getId().getDebitReferenceCode()).get();
            ReferenceCode creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, accountEvent.getId().getCreditReferenceCode()).get();
            accountEvent.setActive(isActive(debitReference,creditReference));
            accountEventRepository.save(accountEvent);
        });
    }

    public boolean isActive(ReferenceCode debitReference, ReferenceCode creditReference) {
        return debitReference.isActive() && creditReference.isActive();

    }
}
