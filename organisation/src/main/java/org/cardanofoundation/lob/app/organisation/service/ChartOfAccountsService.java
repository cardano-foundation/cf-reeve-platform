package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.Constants.VALIDATION_ERROR;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

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

    public Set<ChartOfAccountView> getAllChartOfAccount(String organisationId) {
        return chartOfAccountRepository.findAllByOrganisationId(organisationId).stream().map(ChartOfAccountView::createSuccess).collect(Collectors.toSet());
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
            return Either.left(ChartOfAccountView.createFail(Problem.builder()
                    .withTitle("CHART_OF_ACCOUNT_NOT_FOUND")
                    .withDetail("Unable to find the chart of account with code :%s".formatted(chartOfAccountUpdate.getCustomerCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), chartOfAccountUpdate));
        }
        return Either.right(chartOfAccountOpt.get());
    }

    private Either<ChartOfAccountView, Void> isOrganisationAvaliable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return Either.left(ChartOfAccountView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(), chartOfAccountUpdate));
        }
        return Either.right(null);
    }

    private Either<ChartOfAccountView, Void> isReferenceCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ReferenceCode> referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, chartOfAccountUpdate.getEventRefCode());
        if (referenceCode.isEmpty()) {
            return Either.left(ChartOfAccountView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find event ref code: %s".formatted(chartOfAccountUpdate.getEventRefCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(), chartOfAccountUpdate));
        }
        return Either.right(null);
    }

    private Either<ChartOfAccountView, ChartOfAccountSubType> isSubTypeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        Optional<ChartOfAccountSubType> subType = chartOfAccountSubTypeRepository.findAllByOrganisationIdAndSubTypeId(orgId, chartOfAccountUpdate.getSubType());
        return subType.<Either<ChartOfAccountView, ChartOfAccountSubType>>map(Either::right)
                .orElseGet(() -> Either.left(ChartOfAccountView.createFail(Problem.builder()
                        .withTitle("SUBTYPE_NOT_FOUND")
                        .withDetail("Unable to find subtype code :%s".formatted(chartOfAccountUpdate.getSubType()))
                        .withStatus(Status.NOT_FOUND)
                        .build(), chartOfAccountUpdate)));
    }

    Either<ChartOfAccountView, Void> isParentCodeAvailable(String orgId, ChartOfAccountUpdate chartOfAccountUpdate) {
        if (chartOfAccountUpdate.getParentCustomerCode() != null && !chartOfAccountUpdate.getParentCustomerCode().isEmpty()) {
            Optional<ChartOfAccount> parentChartOfAccount = chartOfAccountRepository.findAllByOrganisationIdAndReferenceCode(orgId, chartOfAccountUpdate.getParentCustomerCode());
            if (parentChartOfAccount.isEmpty()) {
                return Either.left(ChartOfAccountView.createFail(Problem.builder()
                        .withTitle("PARENT_ACCOUNT_NOT_FOUND")
                        .withDetail("Unable to find the parent chart of account with code :%s".formatted(chartOfAccountUpdate.getParentCustomerCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(), chartOfAccountUpdate));
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
            if(isUpsert) {
                chartOfAccount = chartOfAccountOpt.get();
            } else {
                return ChartOfAccountView.createFail(Problem.builder()
                        .withTitle("CHART_OF_ACCOUNT_ALREADY_EXISTS")
                        .withDetail("The chart of account with code :%s already exists".formatted(chartOfAccountUpdate.getCustomerCode()))
                        .withStatus(Status.CONFLICT)
                        .build(), chartOfAccountUpdate);
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
        if(!currency.isEmpty()) {
            Optional<Currency> byId = currencyRepository.findById(new Currency.Id(chartOfAccount.getId().getOrganisationId(), currency));
            if(byId.isEmpty()) {
                return ChartOfAccountView.createFail(Problem.builder()
                        .withTitle("CURRENCY_NOT_FOUND")
                        .withDetail("Unable to find currency with id: %s".formatted(currency))
                        .withStatus(Status.NOT_FOUND)
                        .build(), chartOfAccountUpdate);
            }
        }
        chartOfAccount.setCurrencyId(chartOfAccountUpdate.getCurrency());

        chartOfAccount.setCounterParty(chartOfAccountUpdate.getCounterParty());
        chartOfAccount.setActive(chartOfAccountUpdate.getActive());

        // If opening balance and fcy currency is set then it must be equal to the currency
        if (Optional.ofNullable(chartOfAccountUpdate.getOpeningBalance()).isPresent()
                && Optional.ofNullable(chartOfAccountUpdate.getOpeningBalance().getOriginalCurrencyIdFCY()).isPresent()
                && !chartOfAccountUpdate.getOpeningBalance().getOriginalCurrencyIdFCY().equals(chartOfAccountUpdate.getCurrency())) {
                return ChartOfAccountView.createFail(Problem.builder()
                        .withTitle("OPENING_BALANCE_CURRENCY_MISMATCH")
                        .withDetail("The opening balance FCY currency must match the chart of account currency.")
                        .withStatus(Status.BAD_REQUEST)
                        .build(), chartOfAccountUpdate);
            }


        chartOfAccount.setOpeningBalance(chartOfAccountUpdate.getOpeningBalance());

        ChartOfAccount chartOfAccountResult = chartOfAccountRepository.save(chartOfAccount);
        return ChartOfAccountView.createSuccess(chartOfAccountResult);
    }

    @Transactional
    public Either<Set<Problem>, Set<ChartOfAccountView>> insertChartOfAccountByCsv(String orgId, MultipartFile file) {


        Either<Problem, List<ChartOfAccountUpdateCsv>> lists = csvParser.parseCsv(file, ChartOfAccountUpdateCsv.class);

        if (lists.isLeft()) {
            return Either.left(Set.of(lists.getLeft()));
        }

        List<ChartOfAccountUpdateCsv> chartOfAccountUpdates = lists.get();
        Set<ChartOfAccountView> accountEventViews = new HashSet<>();
        for (ChartOfAccountUpdateCsv chartOfAccountUpdateCsv : chartOfAccountUpdates) {
            Errors errors = validator.validateObject(chartOfAccountUpdateCsv);
            List<ObjectError> allErrors = errors.getAllErrors();
            if (!allErrors.isEmpty()) {
                Problem error = Problem.builder()
                        .withTitle(VALIDATION_ERROR)
                        .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }

            // A workAround to fill the nested object
            try {
                chartOfAccountUpdateCsv.fillOpeningBalance();
            } catch (Exception e) {
                Problem error = Problem.builder()
                        .withTitle("OPENING_BALANCE_ERROR")
                        .withDetail(e.getMessage())
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                accountEventViews.add(ChartOfAccountView.createFail(error, chartOfAccountUpdateCsv));
                continue;
            }

            chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(orgId, chartOfAccountUpdateCsv.getType()).ifPresentOrElse(
                    type -> {
                        type.getSubTypes().stream().filter(subtype -> subtype.getName().equals(chartOfAccountUpdateCsv.getSubType())).findFirst().ifPresentOrElse(
                                subType -> chartOfAccountUpdateCsv.setSubType(String.valueOf(subType.getId())),
                                () -> {
                                    ChartOfAccountSubType subType = ChartOfAccountSubType.builder()
                                            .type(type)
                                            .name(chartOfAccountUpdateCsv.getSubType())
                                            .organisationId(orgId)
                                            .build();
                                    ChartOfAccountSubType save = chartOfAccountSubTypeRepository.save(subType);
                                    chartOfAccountUpdateCsv.setSubType(String.valueOf(save.getId()));
                                }
                        );
                    },
                    () -> {
                        ChartOfAccountSubType subType = saveTypeAndSubType(orgId, chartOfAccountUpdateCsv);
                        chartOfAccountUpdateCsv.setSubType(String.valueOf(subType.getId()));
                    }
            );

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
}
