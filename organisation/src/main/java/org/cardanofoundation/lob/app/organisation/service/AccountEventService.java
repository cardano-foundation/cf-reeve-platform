package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.ACCOUNT_EVENT_MAPPINGS;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

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

    public Either<ProblemDetail, List<AccountEventView>> getAllAccountEvent(String orgId, String customerCode, String name, List<String> creditRefCodes, List<String> debitRefCodes, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> pageables = jpaSortFieldValidator.validateEntity(AccountEvent.class, pageable, ACCOUNT_EVENT_MAPPINGS);
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
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId));
            problem.setTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_REFERNCE_CODE_BY_ID_S_S.formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_REFERNCE_CODE_BY_ID_S_S.formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        ReferenceCode debitReferenceG = debitReference.get();
        ReferenceCode creditReferenceG = creditReference.get();
        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());

        // If the account event already exists and we are not upserting, return an error
        if (accountEventOpt.isPresent() && !isUpsert) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Account with debit reference code %s and credit reference code %s already exists.".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.ACCOUNT_EVENT_ALREADY_EXISTS);
            return AccountEventView.createFail(problem, eventCodeUpdate);
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
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_ORGANISATION_BY_ID_S.formatted(orgId));
            problem.setTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        Optional<ReferenceCode> debitReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode());
        if (debitReference.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_REFERENCE_CODE_BY_ID_S_AND_S_S.formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        Optional<ReferenceCode> creditReference = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, eventCodeUpdate.getCreditReferenceCode());
        if (creditReference.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ErrorTitleConstants.UNABLE_TO_FIND_REFERENCE_CODE_BY_ID_S_AND_S_S.formatted(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }

        Optional<AccountEvent> accountEventOpt = accountEventRepository.findByOrgIdAndDebitReferenceCodeAndCreditReferenceCode(orgId, eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode());
        if (accountEventOpt.isEmpty()){
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account event not found for debit reference code: %s and credit reference code: %s".formatted(eventCodeUpdate.getDebitReferenceCode(), eventCodeUpdate.getCreditReferenceCode()));
            problem.setTitle(ErrorTitleConstants.ACCOUNT_EVENT_NOT_FOUND);
            return AccountEventView.createFail(problem, eventCodeUpdate);
        }
        AccountEvent accountEvent = accountEventOpt.get();
        accountEvent.setName(eventCodeUpdate.getName());
        accountEvent.setActive(isActive(debitReference.get(), creditReference.get()));

        return AccountEventView.convertFromEntity(accountEventRepository.save(accountEvent));
    }

    @Transactional
    public Either<List<ProblemDetail>, List<AccountEventView>> insertAccountEventByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, EventCodeUpdate.class).fold(
                problemDetail ->
                        Either.left(List.of(problemDetail)),
                eventCodeUpdates ->
                        Either.right(eventCodeUpdates.stream().map(eventCodeUpdate -> {
                            Errors errors = validator.validateObject(eventCodeUpdate);
                            List<ObjectError> allErrors = errors.getAllErrors();
                            if (!allErrors.isEmpty()) {
                                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                                problem.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                                return AccountEventView.createFail(problem, eventCodeUpdate);
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

    public void downloadCsv(String orgId, String customerCode, String name, List<String> creditRefCodes, List<String> debitRefCodes, Boolean active, OutputStream outputStream) {
        Page<AccountEvent> accountEvents = accountEventRepository.findAllByOrganisationId(orgId, customerCode, name, creditRefCodes, debitRefCodes, active, Pageable.unpaged());
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Debit Reference Code", "Credit Reference Code", "Name", "Customer Code", "Active"};
            csvWriter.writeNext(header, false);
            for (AccountEvent accountEvent : accountEvents) {
                String[] data = {
                        accountEvent.getId().getDebitReferenceCode(),
                        accountEvent.getId().getCreditReferenceCode(),
                        accountEvent.getName(),
                        accountEvent.getCustomerCode(),
                        String.valueOf(accountEvent.getActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing Account Events to CSV for organisationId {}: {}", orgId, e.getMessage());
        }
    }
}
