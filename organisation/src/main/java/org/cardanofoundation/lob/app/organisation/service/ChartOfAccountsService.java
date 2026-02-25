package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants.OPENING_BALANCE_VALIDATION_ERROR;
import static org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants.VALIDATION_ERROR;
import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.CHART_OF_ACCOUNT_MAPPINGS;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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

import org.cardanofoundation.lob.app.organisation.domain.csv.ChartOfAccountUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.*;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ChartOfAccountView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.CurrencyRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class ChartOfAccountsService {

    private final ChartOfAccountRepository chartOfAccountRepository;
    private final ChartOfAccountTypeRepository chartOfAccountTypeRepository;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    private final ReferenceCodeRepository referenceCodeRepository;
    private final CurrencyRepository currencyRepository;
    private final OrganisationService organisationService;
    private final CsvParser<ChartOfAccountUpdateCsv> csvParser;
    private final Validator validator;
    @PersistenceContext
    private EntityManager entityManager;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Optional<ChartOfAccount> getChartAccount(String organisationId, String customerCode) {
        return chartOfAccountRepository.findByIdAndActive(new ChartOfAccount.Id(organisationId, customerCode), true);
    }

    @Transactional
    public Set<ChartOfAccountType> getAllChartType(String organisationId) {
        return chartOfAccountTypeRepository.findAllByOrganisationId(organisationId);
    }

    public Set<ChartOfAccount> getBySubTypeId(Long subType) {
        return chartOfAccountRepository.findAllByOrganisationIdSubTypeId(subType);
    }



    public Either<ProblemDetail, List<ChartOfAccountView>> getAllChartOfAccount(String organisationId, String customerCode, String name, List<String> currencies, List<String> counterPartyIds, List<String> types, List<String> subTypes, List<String> referenceCodes, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> validateEntity = jpaSortFieldValidator.validateEntity(ChartOfAccount.class, pageable, CHART_OF_ACCOUNT_MAPPINGS);
        if(validateEntity.isLeft()) {
            return Either.left(validateEntity.getLeft());
        }
        pageable = validateEntity.get();
        if(currencies != null) {
            // Lower case to avoid case sensitivity issues
            currencies = currencies.stream().filter(s -> s != null && !s.isEmpty()).map(String::toLowerCase).collect(Collectors.toList());
        }
        return Either.right(chartOfAccountRepository.findAllByOrganisationIdFiltered(organisationId,customerCode, name, currencies, counterPartyIds, types, subTypes, referenceCodes, active, pageable).stream().map(ChartOfAccountView::createSuccess).toList());
    }

    @Transactional
    public ChartOfAccountView updateChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {

        Either<ChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId, chartOfAccountUpdate);
        if (organisationAvaliable.isLeft()) return organisationAvaliable.getLeft();

        Either<ChartOfAccountView, Void> referenceCodeAvailable = isReferenceCodeAvailable(orgId, chartOfAccountUpdate);
        if (referenceCodeAvailable.isLeft()) return referenceCodeAvailable.getLeft();

        Either<ChartOfAccountView, ChartOfAccountSubType> subType = isSubTypeAvailable(orgId, chartOfAccountUpdate);
        if (subType.isLeft()) return subType.getLeft();

        Either<ChartOfAccountView, Void> parentCodeAvailable = isParentCodeAvailable(orgId, chartOfAccountUpdate);
        if (parentCodeAvailable.isLeft()) return parentCodeAvailable.getLeft();

        Either<ChartOfAccountView, ChartOfAccount> chartOfAccountOpt = isChartOfAccountAvailable(orgId, chartOfAccountUpdate);
        if (chartOfAccountOpt.isLeft()) return chartOfAccountOpt.getLeft();

        ChartOfAccount chartOfAccount = chartOfAccountOpt.get();
        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subType, chartOfAccount);

    }

    private Either<ChartOfAccountView, ChartOfAccount> isChartOfAccountAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ChartOfAccount> chartOfAccountOpt = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode());
        if (chartOfAccountOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find the chart of account with code: %s".formatted(chartOfAccountUpdate.getCustomerCode()));
             problem.setTitle("CHART_OF_ACCOUNT_NOT_FOUND");
             problem.setStatus(HttpStatus.NOT_FOUND);
             return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
        }
        return Either.right(chartOfAccountOpt.get());
    }

    private Either<ChartOfAccountView, Void> isOrganisationAvaliable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find Organisation by Id: %s".formatted(orgId));
            problem.setTitle("ORGANISATION_NOT_FOUND");
            return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
        }
        return Either.right(null);
    }

    private Either<ChartOfAccountView, Void> isReferenceCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find event ref code: %s".formatted(chartOfAccountUpdate.getEventRefCode()));
            problem.setTitle("REFERENCE_CODE_NOT_FOUND");
            return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
        }
        return Either.right(null);
    }

    private Either<ChartOfAccountView, ChartOfAccountSubType> isSubTypeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ChartOfAccountSubType> subType = chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());
        return subType.<Either<ChartOfAccountView, ChartOfAccountSubType>>map(Either::right)
                .orElseGet(() -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find subtype code: %s".formatted(chartOfAccountUpdate.getSubType()));
                    problem.setTitle("SUBTYPE_NOT_FOUND");
                    return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
                });
    }

    Either<ChartOfAccountView, Void> isParentCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        if (chartOfAccountUpdate.getParentCustomerCode() != null && !chartOfAccountUpdate.getParentCustomerCode().isEmpty()) {
            Optional<ChartOfAccount> parentChartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode());
            if (parentChartOfAccount.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find the parent chart of account with code: %s".formatted(chartOfAccountUpdate.getParentCustomerCode()));
                problem.setTitle("PARENT_ACCOUNT_NOT_FOUND");
                return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
            }
            if (parentChartOfAccount.get().getId().getCustomerCode().equals(chartOfAccountUpdate.getCustomerCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent chart of account cannot be the same as the account itself: %s".formatted(chartOfAccountUpdate.getCustomerCode()));
                problem.setTitle("PARENT_ACCOUNT_CANNOT_BE_SELF");
                return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
            }
            if (Optional.ofNullable(parentChartOfAccount.get().getParentCustomerCode()).orElse("").equals(chartOfAccountUpdate.getCustomerCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent chart of account: %s cannot have a circular reference to the account itself: %s".formatted(chartOfAccountUpdate.getParentCustomerCode(), chartOfAccountUpdate.getCustomerCode()));
                problem.setTitle("CIRCULAR_REFERENCE");
                return Either.left(ChartOfAccountView.createFail(problem, chartOfAccountUpdate));
            }
        }
        return Either.right(null);
    }

    @Transactional
    public ChartOfAccountView insertChartOfAccount(String orgId, ChartOfAccountUpdate chartOfAccountUpdate, boolean isUpsert) {

        Either<ChartOfAccountView, Void> organisationAvaliable = isOrganisationAvaliable(orgId, chartOfAccountUpdate);
        if (organisationAvaliable.isLeft()) return organisationAvaliable.getLeft();

        Either<ChartOfAccountView, Void> referenceCodeAvailable = isReferenceCodeAvailable(orgId, chartOfAccountUpdate);
        if (referenceCodeAvailable.isLeft()) return referenceCodeAvailable.getLeft();

        Either<ChartOfAccountView, Void> parentCodeAvailable = isParentCodeAvailable(orgId, chartOfAccountUpdate);
        if (parentCodeAvailable.isLeft()) return parentCodeAvailable.getLeft();

        Either<ChartOfAccountView, ChartOfAccountSubType> subTypeAvailable = isSubTypeAvailable(orgId, chartOfAccountUpdate);
        if (subTypeAvailable.isLeft()) return subTypeAvailable.getLeft();

        Optional<ChartOfAccount> chartOfAccountOpt = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getCustomerCode());
        ChartOfAccount chartOfAccount = ChartOfAccount.builder()
                .id(new ChartOfAccount.Id(orgId, chartOfAccountUpdate.getCustomerCode()))
                .build();
        if (chartOfAccountOpt.isPresent()) {
            if (isUpsert) {
                chartOfAccount = chartOfAccountOpt.get();
            } else {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The chart of account with code: %s already exists".formatted(chartOfAccountUpdate.getCustomerCode()));
                problem.setTitle("CHART_OF_ACCOUNT_ALREADY_EXISTS");
                return ChartOfAccountView.createFail(problem, chartOfAccountUpdate);
            }
        }

        return updateAndSaveChartOfAccount(chartOfAccountUpdate, subTypeAvailable, chartOfAccount);

    }

    private ChartOfAccountView updateAndSaveChartOfAccount(ChartOfAccountUpdate chartOfAccountUpdate, Either<ChartOfAccountView, ChartOfAccountSubType> subType, ChartOfAccount chartOfAccount) {
        chartOfAccount.setName(chartOfAccountUpdate.getName());
        chartOfAccount.setEventRefCode(chartOfAccountUpdate.getEventRefCode());
        chartOfAccount.setSubType(subType.get());
        chartOfAccount.setParentCustomerCode(chartOfAccountUpdate.getParentCustomerCode() == null || chartOfAccountUpdate.getParentCustomerCode().isEmpty() ? null : chartOfAccountUpdate.getParentCustomerCode());
        String currency = Optional.ofNullable(chartOfAccountUpdate.getCurrency()).orElse("");
        if (!currency.isEmpty()) {
            Optional<Currency> byId = currencyRepository.findById(new Currency.Id(chartOfAccount.getId().getOrganisationId(), currency));
            if (byId.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find currency with id: %s".formatted(currency));
                problem.setTitle("CURRENCY_NOT_FOUND");
                return ChartOfAccountView.createFail(problem, chartOfAccountUpdate);
            }
        }
        chartOfAccount.setCurrencyId(chartOfAccountUpdate.getCurrency());

        chartOfAccount.setCounterParty(chartOfAccountUpdate.getCounterParty());
        chartOfAccount.setActive(chartOfAccountUpdate.getActive());

        // If opening balance and fcy currency is set then it must be equal to the currency
        if (Optional.ofNullable(chartOfAccountUpdate.getOpeningBalance()).isPresent() && !chartOfAccountUpdate.getOpeningBalance().allNull()) {
            Errors errors = validator.validateObject(chartOfAccountUpdate.getOpeningBalance());
            List<ObjectError> allErrors = errors.getAllErrors();
            if (!allErrors.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                error.setTitle(OPENING_BALANCE_VALIDATION_ERROR);
                return ChartOfAccountView.createFail(error, chartOfAccountUpdate);
            }
            if (!chartOfAccountUpdate.getOpeningBalance().getOriginalCurrencyIdFCY().equals(chartOfAccountUpdate.getCurrency())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The opening balance FCY currency must match the chart of account currency.");
                problem.setTitle("OPENING_BALANCE_CURRENCY_MISMATCH");
                return ChartOfAccountView.createFail(problem, chartOfAccountUpdate);
            }
            Organisation organisation = organisationService.findById(chartOfAccount.getId().getOrganisationId()).orElseThrow();
            Currency organisationCurrency = currencyRepository.findByCurrencyId(chartOfAccount.getId().getOrganisationId(), organisation.getCurrencyId()).orElseThrow(() -> new RuntimeException("Organisation currency not found"));
            if (!chartOfAccountUpdate.getOpeningBalance().getOriginalCurrencyIdLCY().equals(organisationCurrency.getId().getCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The opening balance LCY currency must match the organisation currency: %s".formatted(organisationCurrency.getId().getCode()));
                problem.setTitle("OPENING_BALANCE_CURRENCY_MISMATCH");
                return ChartOfAccountView.createFail(problem, chartOfAccountUpdate);
            }
        }


        chartOfAccount.setOpeningBalance(chartOfAccountUpdate.getOpeningBalance());

        ChartOfAccount chartOfAccountResult = chartOfAccountRepository.save(chartOfAccount);
        return ChartOfAccountView.createSuccess(chartOfAccountResult);
    }

    @Transactional
    public Either<List<ProblemDetail>, List<ChartOfAccountView>> insertChartOfAccountByCsv(String orgId, MultipartFile file) {


        Either<ProblemDetail, List<ChartOfAccountUpdateCsv>> lists = csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class);

        if (lists.isLeft()) {
            return Either.left(List.of(lists.getLeft()));
        }

        List<ChartOfAccountUpdateCsv> chartOfAccountUpdates = lists.get();
        List<ChartOfAccountView> accountEventViews = new ArrayList<>();
        for (ChartOfAccountUpdateCsv chartOfAccountUpdateCsv : chartOfAccountUpdates) {
            Errors errors = validator.validateObject(chartOfAccountUpdateCsv);
            List<ObjectError> allErrors = errors.getAllErrors();
            if (!allErrors.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                error.setTitle(VALIDATION_ERROR);
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }

            // A workAround to fill the nested object
            try {
                chartOfAccountUpdateCsv.fillOpeningBalance();
            } catch (Exception e) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
                error.setTitle("OPENING_BALANCE_ERROR");
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }


            Optional<ChartOfAccountType> type = chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, chartOfAccountUpdateCsv.getType());
            if( type.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Chart of account type: %s not found. Please provide a valid type.".formatted(chartOfAccountUpdateCsv.getType()));
                error.setTitle("CHART_OF_ACCOUNT_TYPE_NOT_FOUND");
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }
            Optional<ChartOfAccountSubType> subType = chartOfAccountSubTypeRepository.findFirstByNameAndOrganisationIdAndParentName(orgId, chartOfAccountUpdateCsv.getSubType(), chartOfAccountUpdateCsv.getType());
            if (subType.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Chart of account subtype: %s not found for type: %s. Please provide a valid subtype.".formatted(chartOfAccountUpdateCsv.getSubType(), chartOfAccountUpdateCsv.getType()));
                error.setTitle("CHART_OF_ACCOUNT_SUBTYPE_NOT_FOUND");
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }
            chartOfAccountUpdateCsv.setType(String.valueOf(type.get().getId()));
            chartOfAccountUpdateCsv.setSubType(String.valueOf(subType.get().getId()));
            ChartOfAccountView accountEventView = insertChartOfAccount(orgId, chartOfAccountUpdateCsv, true);

            accountEventViews.add(accountEventView);
        }
        return Either.right(accountEventViews);
    }

    private ChartOfAccountSubType saveTypeAndSubType(String orgId, ChartOfAccountUpdateCsv chartOfAccountUpdateCsv) {

        ChartOfAccountType chartOfAccountType = ChartOfAccountType.builder()
                .organisationId(orgId)
                .name(chartOfAccountUpdateCsv.getType())
                .build();
        ChartOfAccountSubType chartOfAccountSubType = ChartOfAccountSubType.builder()
                .organisationId(orgId)
                .name(chartOfAccountUpdateCsv.getSubType())
                .type(chartOfAccountType)
                .build();
        chartOfAccountType.setSubTypes(Set.of(chartOfAccountSubType));
        ChartOfAccountType save = chartOfAccountTypeRepository.save(chartOfAccountType);
        return save.getSubTypes().stream().iterator().next();
    }

    public void downloadCsv(String orgId, String customerCode, String name, List<String> currencies, List<String> counterPartyIds, List<String> types, List<String> subTypes, List<String> referenceCodes, Boolean active, OutputStream outputStream) {
        Page<ChartOfAccount> chartOfAccounts = chartOfAccountRepository.findAllByOrganisationIdFiltered(orgId, customerCode, name, currencies, counterPartyIds, types, subTypes, referenceCodes, active, Pageable.unpaged());
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Customer Code","Reference Code","Name","Type","Sub Type","Currency","CounterParty","Parent Customer Code","Active","Open Balance FCY","Open Balance LCY","Open Balance Currency ID FCY","Open Balance Currency ID LCY","Open Balance Type","Open Balance Date"};
            csvWriter.writeNext(header, false);
            for (ChartOfAccount coa : chartOfAccounts) {
                OpeningBalance openingBalance = Optional.ofNullable(coa.getOpeningBalance()).orElse(new OpeningBalance());
                String[] data = {
                        coa.getId().getCustomerCode(),
                        coa.getEventRefCode(),
                        coa.getName(),
                        coa.getSubType().getType().getName(),
                        coa.getSubType().getName(),
                        coa.getCurrencyId(),
                        coa.getCounterParty(),
                        coa.getParentCustomerCode(),
                        String.valueOf(coa.getActive()),
                        openingBalance.getBalanceFCY() != null ? openingBalance.getBalanceFCY().toString() : null,
                        openingBalance.getBalanceLCY() != null ? openingBalance.getBalanceLCY().toString() : null,
                        openingBalance.getOriginalCurrencyIdFCY(),
                        openingBalance.getOriginalCurrencyIdLCY(),
                        openingBalance.getBalanceType() != null ? openingBalance.getBalanceType().name() : "",
                        openingBalance.getDate() != null ? openingBalance.getDate().toString() : ""

                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (IOException e) {
            log.error("Failed to download chart of account csv.", e);
        }
    }
}
