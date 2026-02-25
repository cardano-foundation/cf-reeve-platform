package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.CURRENCY_MAPPINGS;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.request.CurrencyUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.CurrencyView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@RequiredArgsConstructor
@Slf4j
@Service
public class CurrencyService {

    private final CurrencyRepository currencyRepository;
    private final CsvParser<CurrencyUpdate> csvParser;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;
    private final ChartOfAccountRepository chartOfAccountRepository;

    public Either<ProblemDetail, List<CurrencyView>> getAllCurrencies(String orgId, String customerCode, List<String> currencyIds, Pageable pageable) {
        Either<ProblemDetail, Pageable> pageables = jpaSortFieldValidator.validateEntity(Currency.class, pageable, CURRENCY_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        return Either.right(currencyRepository.findAllByOrganisationId(orgId, customerCode, currencyIds, pageable)
                .stream()
                .map(currency -> CurrencyView.createSuccess(currency.getId().getCode(), currency.getIsoCode(), currency.isActive()))
                .toList());
    }

    public Optional<Currency> findByOrganisationIdAndCode(@Param("organisationId") String organisationId,
                                                          @Param("customerCode") String customerCode) {
        return currencyRepository.findById(new Currency.Id(organisationId, customerCode));
    }

    public Set<Currency> findAllByOrganisationId(@Param("organisationId") String organisationId ){
        return currencyRepository.findAllByOrganisationId(organisationId);
    }

    public CurrencyView updateCurrency(String orgId, @Valid CurrencyUpdate currencyUpdate) {
        return currencyRepository.findById(new Currency.Id(orgId, currencyUpdate.getCode()))
                .map(currency -> {
                    if(currencyUpdate.getActive() != currency.isActive()) {
                        if(canUpdateCurrencyActive(currency)) {
                            currency.setActive(currencyUpdate.getActive());
                        } else {
                            ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Currency with customer code " + currencyUpdate.getCode() + " is in use and cannot be deactivated");
                            error.setTitle("CURRENCY_IN_USE_CANNOT_DEACTIVATE");
                            return CurrencyView.createFail(error, currencyUpdate);
                        }
                    }
                    currency.setIsoCode(currencyUpdate.getIsoCode());
                    Currency updatedEntity = currencyRepository.save(currency);
                    return CurrencyView.createSuccess(updatedEntity.getId().getCode(), updatedEntity.getIsoCode(), currency.isActive());
                })
                .orElseGet(() -> {
                    ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Currency with customer code " + currencyUpdate.getCode() + " not found");
                    error.setTitle(ErrorTitleConstants.CURRENCY_NOT_FOUND);
                    return CurrencyView.createFail(error, currencyUpdate);
                });
    }

    // A currency active can be update if it's inactive or if it's active and not used in any chart of account
    private boolean canUpdateCurrencyActive(Currency currency) {
        return !currency.isActive() || currency.isActive() && chartOfAccountRepository.findTopByCurrencyIdAndIdOrganisationId(currency.getId().getCode(), currency.getId().getOrganisationId()).isEmpty();
    }

    public CurrencyView insertCurrency(String orgId, @Valid CurrencyUpdate currencyUpdate, boolean isUpsert) {
        Optional<Currency> currencyFound = currencyRepository.findById(new Currency.Id(orgId, currencyUpdate.getCode()));
        Currency currency = new Currency(new Currency.Id(orgId, currencyUpdate.getCode()), currencyUpdate.getIsoCode(), currencyUpdate.getActive());
        if(currencyFound.isPresent()) {
            if(isUpsert) {
                currency = currencyFound.get();
                if(currencyUpdate.getActive() != currency.isActive()) {
                    if(canUpdateCurrencyActive(currency)) {
                        currency.setActive(currencyUpdate.getActive());
                    } else {
                        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Currency with customer code " + currencyUpdate.getCode() + " is in use and cannot be deactivated");
                        error.setTitle("CURRENCY_IN_USE_CANNOT_DEACTIVATE");
                        return CurrencyView.createFail(error, currencyUpdate);
                    }
                }
                currency.setIsoCode(currencyUpdate.getIsoCode());
            } else {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Currency with customer code " + currencyUpdate.getCode() + " already exists");
                error.setTitle(ErrorTitleConstants.CURRENCY_ALREADY_EXISTS);
                return CurrencyView.createFail(error, currencyUpdate);
            }
        }
        Currency save = currencyRepository.save(currency);
        return CurrencyView.createSuccess(save.getId().getCode(), save.getIsoCode(), currency.isActive());
    }

    public Optional<CurrencyView> getCurrency(String orgId, String customerCode) {
        return currencyRepository.findById(new Currency.Id(orgId, customerCode))
                .map(currency -> CurrencyView.createSuccess(currency.getId().getCode(), currency.getIsoCode(), currency.isActive()));
    }

    public Either<List<ProblemDetail>, List<CurrencyView>> insertViaCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CurrencyUpdate.class).fold(
                problemDetail -> Either.left(List.of(problemDetail)),
                currencyUpdates -> Either.right(currencyUpdates.stream().map(currencyUpdate -> {
                    Errors errors = validator.validateObject(currencyUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        error.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return CurrencyView.createFail(error, currencyUpdate);
                    }
                    return insertCurrency(orgId, currencyUpdate, true);
                }).toList())
        );
    }

    public void downloadCsv(String orgId, String code, List<String> isoCodes, OutputStream outputStream) {
        Page<Currency> allCurrencies = currencyRepository.findAllByOrganisationId(orgId, code, isoCodes, Pageable.unpaged());
        try(Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Code", "ISO Code", "Active"};
            csvWriter.writeNext(header, false);
            for (Currency currency : allCurrencies) {
                String[] data = {
                        currency.getId().getCode(),
                        currency.getIsoCode(),
                        String.valueOf(currency.isActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing currencies to CSV", e);
        }
    }
}
