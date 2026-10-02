package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountingRegime;
import org.cardanofoundation.lob.app.organisation.domain.request.AccountingRegimeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.AccountingRegimeView;
import org.cardanofoundation.lob.app.organisation.repository.AccountingRegimeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;

@RequiredArgsConstructor
@Slf4j
@Service
public class AccountingRegimeService {

    private final AccountingRegimeRepository accountingRegimeRepository;
    private final CsvParser<AccountingRegimeUpdate> csvParser;
    private final Validator validator;

    public List<AccountingRegimeView> getAllAccountingRegimes(String orgId) {
        return accountingRegimeRepository.findAllByOrganisationId(orgId).stream()
                .map(regime -> AccountingRegimeView.createSuccess(regime.getId().getCode(), regime.getLabel(), regime.isActive()))
                .toList();
    }

    public Set<AccountingRegime> findAllByOrganisationId(String organisationId) {
        return accountingRegimeRepository.findAllByOrganisationId(organisationId);
    }

    public Optional<AccountingRegimeView> getAccountingRegime(String orgId, String code) {
        return accountingRegimeRepository.findById(new AccountingRegime.Id(orgId, code))
                .map(regime -> AccountingRegimeView.createSuccess(regime.getId().getCode(), regime.getLabel(), regime.isActive()));
    }

    public AccountingRegimeView updateAccountingRegime(String orgId, @Valid AccountingRegimeUpdate accountingRegimeUpdate) {
        return accountingRegimeRepository.findById(new AccountingRegime.Id(orgId, accountingRegimeUpdate.getCode()))
                .map(regime -> {
                    regime.setLabel(accountingRegimeUpdate.getLabel());
                    regime.setActive(accountingRegimeUpdate.getActive());
                    AccountingRegime updatedEntity = accountingRegimeRepository.save(regime);
                    return AccountingRegimeView.createSuccess(updatedEntity.getId().getCode(), updatedEntity.getLabel(), updatedEntity.isActive());
                })
                .orElseGet(() -> {
                    ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Accounting regime with code " + accountingRegimeUpdate.getCode() + " not found");
                    error.setTitle(ErrorTitleConstants.ACCOUNTING_REGIME_NOT_FOUND);
                    return AccountingRegimeView.createFail(error, accountingRegimeUpdate);
                });
    }

    public AccountingRegimeView insertAccountingRegime(String orgId, @Valid AccountingRegimeUpdate accountingRegimeUpdate, boolean isUpsert) {
        Optional<AccountingRegime> regimeFound = accountingRegimeRepository.findById(new AccountingRegime.Id(orgId, accountingRegimeUpdate.getCode()));
        AccountingRegime regime = new AccountingRegime(new AccountingRegime.Id(orgId, accountingRegimeUpdate.getCode()), accountingRegimeUpdate.getLabel(), accountingRegimeUpdate.getActive());
        if (regimeFound.isPresent()) {
            if (isUpsert) {
                regime = regimeFound.get();
                regime.setLabel(accountingRegimeUpdate.getLabel());
                regime.setActive(accountingRegimeUpdate.getActive());
            } else {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Accounting regime with code " + accountingRegimeUpdate.getCode() + " already exists");
                error.setTitle(ErrorTitleConstants.ACCOUNTING_REGIME_ALREADY_EXISTS);
                return AccountingRegimeView.createFail(error, accountingRegimeUpdate);
            }
        }
        AccountingRegime save = accountingRegimeRepository.save(regime);
        return AccountingRegimeView.createSuccess(save.getId().getCode(), save.getLabel(), save.isActive());
    }

    public Either<ProblemDetail, List<AccountingRegimeView>> insertViaCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, AccountingRegimeUpdate.class).fold(
                Either::left,
                regimeUpdates -> Either.right(regimeUpdates.stream().map(regimeUpdate -> {
                    Errors errors = validator.validateObject(regimeUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        error.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return AccountingRegimeView.createFail(error, regimeUpdate);
                    }
                    return insertAccountingRegime(orgId, regimeUpdate, true);
                }).toList())
        );
    }

}
