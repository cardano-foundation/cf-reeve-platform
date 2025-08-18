package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;


import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType.MONTH;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType.QUARTER;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType.YEAR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.USER;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.BALANCE_SHEET;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.INCOME_STATEMENT;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.PublishError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.PublicReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportGenerateRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportReprocessRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;
import org.cardanofoundation.lob.app.support.security.AuthenticationUserService;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {


    private final ReportRepository reportRepository;
    private final PublicReportRepository publicReportRepository;
    private final OrganisationPublicApi organisationPublicApi;
    private final Clock clock;
    private final AuthenticationUserService authenticationUserService;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final ReportTypeRepository reportTypeRepository;
    private final TransactionItemRepository transactionItemRepository;

    @Transactional
    public Either<Problem, ReportEntity> approveReportForLedgerDispatch(String reportId) {
        Optional<ReportEntity> reportM = reportRepository.findById(reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(reportId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_ID, reportId)
                    .build());
        }
        ReportEntity report = reportM.orElseThrow();
        if (Boolean.FALSE.equals(report.getIsReadyToPublish())) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_READY_FOR_PUBLISHING)
                    .withDetail(Constants.REPORT_WITH_ID_S_IS_NOT_READY_FOR_PUBLISHING.formatted(reportId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_ID, reportId)
                    .build());
        }
        report.setLedgerDispatchApproved(true);
        report.setLedgerDispatchDate(LocalDateTime.now(clock));
        report.setPublishedBy(authenticationUserService.getCurrentUser());

        reportRepository.save(report);

        return Either.right(report);
    }

    public boolean exists(String reportId) {
        return reportRepository.existsById(reportId);
    }

    public Optional<ReportEntity> findById(String reportId) {
        return reportRepository.findById(reportId);
    }

    @Transactional
    @Deprecated
    public Either<Problem, Void> storeIncomeStatementAsExample(String organisationId) {
        log.info("Income Statement::Saving report example...");

        Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail(Constants.ORGANISATION_WITH_ID_S_DOES_NOT_EXIST.formatted(organisationId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.ORGANISATION_ID, organisationId)
                    .build());
        }
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = orgM.orElseThrow();

        ReportEntity reportExample = new ReportEntity();
        reportExample.setVer(clock.millis());
        reportExample.setIdControl(Report.idControl(organisationId, INCOME_STATEMENT, MONTH, (short) 2023, Optional.of((short) 3)));
        reportExample.setReportId(Report.id(organisationId, INCOME_STATEMENT, MONTH, (short) 2023, reportExample.getVer(), Optional.of((short) 3)));

        reportExample.setOrganisation(Organisation.builder()
                .id(organisationId)
                .countryCode(org.getCountryCode())
                .name(org.getName())
                .taxIdNumber(org.getTaxIdNumber())
                .currencyId(org.getCurrencyId())
                .build()
        );

        reportExample.setType(INCOME_STATEMENT);
        reportExample.setIntervalType(MONTH); // Assuming MONTHLY is a constant in ReportRollupPeriodType
        reportExample.setYear((short) 2023);
        reportExample.setPeriod(Optional.of((short) 3)); // Representing March
        reportExample.setMode(USER); // Assuming USER is a constant in ReportMode enum
        reportExample.setDate(LocalDate.now(clock));

        IncomeStatementData incomeStatementReportData = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(new BigDecimal("10000.90"))
                        .buildOfLongTermProvision(new BigDecimal("1000000.10"))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(new BigDecimal("500000.15"))
                        .build())
                .financialIncome(IncomeStatementData.FinancialIncome.builder()
                        .financialRevenues(new BigDecimal("200000.53"))
                        .netIncomeOptionsSale(new BigDecimal("100000.10"))
                        .realisedGainsOnSaleOfCryptocurrencies(new BigDecimal("50000.15"))
                        .stakingRewardsIncome(new BigDecimal("10000.53"))
                        .financialExpenses(new BigDecimal("20000.10"))
                        .build())
                .extraordinaryIncome(IncomeStatementData.ExtraordinaryIncome.builder()
                        .extraordinaryExpenses(new BigDecimal("10000.10"))
                        .build())
                .taxExpenses(IncomeStatementData.TaxExpenses.builder()
                        .directTaxes(new BigDecimal("1000.51"))
                        .build())
                .operatingExpenses(IncomeStatementData.OperatingExpenses.builder()
                        .personnelExpenses(new BigDecimal("500000.15"))
                        .generalAndAdministrativeExpenses(new BigDecimal("200000.53"))
                        .build())
                .build();

        incomeStatementReportData = incomeStatementReportData.toBuilder().profitForTheYear(incomeStatementReportData.sumOf()).build();

        reportExample.setIncomeStatementReportData(Optional.of(incomeStatementReportData));

        ReportEntity savedEntity = reportRepository.save(reportExample);

        log.info("Income Statement::Report saved successfully: {}", savedEntity.getReportId());

        return Either.right(null);
    }

    @Transactional
    @Deprecated
    public Either<Problem, Void> storeBalanceSheetAsExample(String organisationId) {
        log.info("Balance Sheet:: Saving report...");

        Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail(Constants.ORGANISATION_WITH_ID_S_DOES_NOT_EXIST.formatted(organisationId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.ORGANISATION_ID, organisationId)
                    .build());
        }
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = orgM.orElseThrow();

        ReportEntity reportExample = new ReportEntity();
        reportExample.setVer(clock.millis());
        reportExample.setIdControl(Report.idControl(organisationId, BALANCE_SHEET, MONTH, (short) 2023, Optional.of((short) 3)));
        reportExample.setReportId(Report.id(organisationId, INCOME_STATEMENT, YEAR, (short) 2024, reportExample.getVer(), Optional.empty()));

        reportExample.setOrganisation(Organisation.builder()
                .id(organisationId)
                .countryCode(org.getCountryCode())
                .name(org.getName())
                .taxIdNumber(org.getTaxIdNumber())
                .currencyId(org.getCurrencyId())
                .build()
        );

        reportExample.setType(BALANCE_SHEET);
        reportExample.setIntervalType(MONTH); // Assuming MONTHLY is a constant in ReportRollupPeriodType
        reportExample.setYear((short) 2023);
        reportExample.setPeriod(Optional.of((short) 3)); // Representing March
        reportExample.setMode(USER); // Assuming USER is a constant in ReportMode enum
        reportExample.setDate(LocalDate.now(clock));

        BalanceSheetData balanceSheetReportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("265306.12"))
                                .intangibleAssets(new BigDecimal("63673.47"))
                                .investments(new BigDecimal("106122.45"))
                                .financialAssets(new BigDecimal("79591.84"))
                                .build())
                        .currentAssets(BalanceSheetData.Assets.CurrentAssets.builder()
                                .prepaymentsAndOtherShortTermAssets(new BigDecimal("15918.37"))
                                .otherReceivables(new BigDecimal("26530.61"))
                                .cryptoAssets(new BigDecimal("53061.22"))
                                .cashAndCashEquivalents(new BigDecimal("39795.92"))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("20000.00"))
                                .build())
                        .currentLiabilities(BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                .tradeAccountsPayables(new BigDecimal("15000.00"))
                                .otherShortTermLiabilities(new BigDecimal("10000.00"))
                                .accrualsAndShortTermProvisions(new BigDecimal("5000.00"))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("300000.00"))
                        .profitForTheYear(new BigDecimal("100000.00"))
                        .resultsCarriedForward(new BigDecimal("200000.00"))
                        .build())
                .build();

        reportExample.setBalanceSheetReportData(Optional.of(balanceSheetReportData));

        if (!reportExample.isValid()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.INVALID_REPORT)
                    .withDetail(Constants.REPORT_IS_NOT_VALID_SINCE_IT_DIDN_T_PASS_THROUGH_BUSINESS_CHECKS)
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_ID, reportExample.getReportId())
                    .with(Constants.REPORT_TYPE, reportExample.getType())
                    .build());
        }

        ReportEntity savedEntity = reportRepository.save(reportExample);

        log.info("Balance Sheet::Report saved successfully: {}", savedEntity.getReportId());

        return Either.right(null);
    }


    public Either<Problem, ReportEntity> storeReport(ReportType reportType,
                                                     CreateReportView createReportView,
                                                     IntervalType intervalType,
                                                     short year,
                                                     short period) {
        log.info(reportType.name() + ":: Saving report...");
        if (reportType == BALANCE_SHEET && createReportView.getBalanceSheetData().isEmpty() ||
                reportType == INCOME_STATEMENT && createReportView.getIncomeStatementData().isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.INVALID_REPORT_TYPE)
                    .withDetail(Constants.REPORT_TYPE_IS_NOT_VALID_EXPECTED_BALANCE_SHEET_BUT_GOT_S.formatted(reportType))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_TYPE, reportType)
                    .build());
        }
        String organisationId = createReportView.getOrganisationId();
        Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail(Constants.ORGANISATION_WITH_ID_S_DOES_NOT_EXIST.formatted(organisationId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.ORGANISATION_ID, organisationId)
                    .build());
        }
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = orgM.orElseThrow();

        Either<Problem, ReportEntity> reportEntityE = exist(organisationId, reportType, intervalType, year, period);
        ReportEntity reportEntity = reportEntityE.fold(problem -> {
            // question: is it safe to assume that problem will always be because it already exists?

            return newReport();
        }, success -> {
            if (Boolean.TRUE.equals(success.getLedgerDispatchApproved())) {
                return newReport(success.getVer());
            }
            return success;
        });

        reportEntity.setReportId(Report.id(organisationId, reportType, intervalType, year, reportEntity.getVer(), Optional.of(period)));
        reportEntity.setIdControl(Report.idControl(organisationId, reportType, intervalType, year, Optional.of(period)));

        reportEntity.setOrganisation(Organisation.builder()
                .id(organisationId)
                .countryCode(org.getCountryCode())
                .name(org.getName())
                .taxIdNumber(org.getTaxIdNumber())
                .currencyId(org.getCurrencyId())
                .build()
        );

        reportEntity.setType(reportType);
        reportEntity.setIntervalType(intervalType); // Assuming MONTHLY is a constant in ReportRollupPeriodType
        reportEntity.setYear(year);
        reportEntity.setPeriod(Optional.of(period)); // Representing March
        reportEntity.setMode(USER); // Assuming USER is a constant in ReportMode enum
        reportEntity.setDate(LocalDate.now(clock));

        if (reportType == BALANCE_SHEET) {
            reportEntity.setBalanceSheetReportData(createReportView.getBalanceSheetData());
        } else if (reportType == INCOME_STATEMENT) {
            reportEntity.setIncomeStatementReportData(createReportView.getIncomeStatementData());
        }

        Either<Problem, Void> isReportReadyToPublish = canPublish(reportEntity);
        reportEntity.setIsReadyToPublish(true);
        if (isReportReadyToPublish.isLeft()) {
            reportEntity.setPublishError(PublishError.valueOf(isReportReadyToPublish.getLeft().getTitle()));
            reportEntity.setIsReadyToPublish(false);
        }

        ReportEntity result = reportRepository.save(reportEntity);

        ReportType relatedReportType = reportEntity.getType().equals(INCOME_STATEMENT) ? BALANCE_SHEET : INCOME_STATEMENT;
        String relatedReportId = Report.idControl(reportEntity.getOrganisation().getId(), relatedReportType, reportEntity.getIntervalType(), reportEntity.getYear(), reportEntity.getPeriod());
        Optional<ReportEntity> relatedReportM = reportRepository.findLatestByIdControl(reportEntity.getOrganisation().getId(), relatedReportId);

        if (relatedReportM.isPresent()) {
            ReportReprocessRequest relatedReport = new ReportReprocessRequest();
            relatedReport.setReportId(relatedReportM.get().getReportId());
            relatedReport.setOrganisationId(organisationId);
            reportReprocess(relatedReport);
        }


        log.info(reportType.name() + "::Report saved successfully: {}", result.getReportId());

        return Either.right(result);
    }

    public Set<ReportEntity> findAllByOrgId(String organisationId) {
        return reportRepository.findAllByOrganisationId(organisationId);
    }

    public Set<ReportEntity> findAllByTypeAndPeriod(String organistionId, ReportType reportType, IntervalType intervalType, short year, short period) {
        return publicReportRepository.findAllByTypeAndPeriod(organistionId, reportType, intervalType, year, period);
    }

    public Either<Problem, ReportEntity> exist(String organisationId, ReportType reportType, IntervalType intervalType, short year, short period) {
        String reportId = Report.idControl(organisationId, reportType, intervalType, year, Optional.of(period));
        Optional<ReportEntity> reportM = reportRepository.findLatestByIdControl(organisationId, reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(reportId))
                    .withStatus(Status.NOT_FOUND)
                    .with(Constants.REPORT_ID, reportId)
                    .build());
        }

        ReportEntity report = reportM.orElseThrow();

        return Either.right(report);
    }

    public Either<Problem, Boolean> isReportValid(String reportId) {
        Optional<ReportEntity> reportM = reportRepository.findById(reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(reportId))
                    .withStatus(Status.NOT_FOUND)
                    .with(Constants.REPORT_ID, reportId)
                    .build());
        }

        ReportEntity report = reportM.orElseThrow();

        return Either.right(report.isValid());
    }

    public Either<Problem, Void> store(String organisationId,
                                       IntervalType intervalType,
                                       short year,
                                       Integer ver,
                                       Optional<Short> period,
                                       Either<IncomeStatementData, BalanceSheetData> reportData) {
        Optional<org.cardanofoundation.lob.app.organisation.domain.entity.Organisation> orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail(Constants.ORGANISATION_WITH_ID_S_DOES_NOT_EXIST.formatted(organisationId))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.ORGANISATION_ID, organisationId)
                    .build());
        }
        org.cardanofoundation.lob.app.organisation.domain.entity.Organisation org = orgM.orElseThrow();

        ReportType reportType = reportData.isLeft() ? INCOME_STATEMENT : BALANCE_SHEET;

        String reportId = Report.id(organisationId, reportType, intervalType, year, ver, period);
        Optional<ReportEntity> existingReportM = reportRepository.findById(reportId);

        ReportEntity reportEntity = new ReportEntity();
        if (existingReportM.isPresent()) {
            reportEntity = existingReportM.orElseThrow();
            // Prevent overwriting approved reports
            if (Boolean.TRUE.equals(reportEntity.getLedgerDispatchApproved())) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.REPORT_ALREADY_APPROVED)
                        .withDetail(Constants.REPORT_WITH_ID_S_HAS_ALREADY_BEEN_APPROVED_FOR_LEDGER_DISPATCH.formatted(reportId))
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportId)
                        .build());
            }
        } else {
            reportEntity.setReportId(reportId);
            reportEntity.setType(reportType);
            reportEntity.setIntervalType(intervalType);
            reportEntity.setYear(year);
            reportEntity.setPeriod(period);
            reportEntity.setMode(USER);
            reportEntity.setDate(LocalDate.now(clock));

            reportEntity.setOrganisation(Organisation.builder()
                    .id(organisationId)
                    .countryCode(org.getCountryCode())
                    .name(org.getName())
                    .taxIdNumber(org.getTaxIdNumber())
                    .currencyId(org.getCurrencyId())
                    .build()
            );
        }

        // Validate profitForTheYear consistency between IncomeStatementData and BalanceSheetData
        ReportType relatedReportType = reportData.isLeft() ? BALANCE_SHEET : INCOME_STATEMENT;
        String relatedReportId = Report.id(organisationId, relatedReportType, intervalType, year, ver, period);
        Optional<ReportEntity> relatedReportM = reportRepository.findById(relatedReportId);

        if (relatedReportM.isPresent()) {
            ReportEntity relatedReport = relatedReportM.orElseThrow();
            Optional<Optional<BigDecimal>> relatedProfit = relatedReport.getIncomeStatementReportData()
                    .map(IncomeStatementData::getProfitForTheYear)
                    .or(() -> relatedReport.getBalanceSheetReportData().flatMap(bsd -> bsd.getCapital().map(BalanceSheetData.Capital::getProfitForTheYear)));

            if (relatedProfit.isPresent()) {
                BigDecimal newProfit = reportData.isLeft()
                        ? reportData.getLeft().getProfitForTheYear().orElse(BigDecimal.ZERO)
                        : reportData.get().getCapital().flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

                if (!newProfit.equals(relatedProfit.get().orElse(BigDecimal.ZERO))) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.PROFIT_FOR_THE_YEAR_MISMATCH)
                            .withDetail(Constants.PROFIT_FOR_THE_YEAR_DOES_NOT_MATCH_THE_RELATED_REPORT)
                            .withStatus(Status.BAD_REQUEST)
                            .with(Constants.REPORT_ID, reportId)
                            .build());
                }
            }
        }

        Either<Problem, Void> emptyCheckE = checkIfEmpty(reportId, reportData);
        if (emptyCheckE.isLeft()) {
            return emptyCheckE;
        }

        if (reportData.isLeft()) {
            IncomeStatementData reportDataLeft = reportData.getLeft();

            if (!reportDataLeft.isValid()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_REPORT_DATA)
                        .withDetail(Constants.INCOME_STATEMENT_REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED)
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportId)
                        .build());
            }

            reportEntity.setIncomeStatementReportData(Optional.of(reportData.getLeft()));
        } else {
            if (!reportData.get().isValid()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_REPORT_DATA)
                        .withDetail(Constants.BALANCE_SHEET_REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED)
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportId)
                        .build());
            }

            reportEntity.setBalanceSheetReportData(Optional.of(reportData.get()));
        }
        Either<Problem, Void> isReadyToPublish = canPublish(reportEntity);
        reportEntity.setIsReadyToPublish(true);
        if (isReadyToPublish.isLeft()) {
            reportEntity.setPublishError(PublishError.valueOf(isReadyToPublish.getLeft().getTitle()));
            reportEntity.setIsReadyToPublish(false);
        }
        reportRepository.save(reportEntity);

        return Either.right(null);
    }

    private Either<Problem, Void> checkIfEmpty(String reportId, Either<IncomeStatementData, BalanceSheetData> reportData) {
        if (reportData.isLeft() && BigDecimal.ZERO.equals(reportData.getLeft().sumOf())) {
            return emptyReportData(reportId);
        }
        if (reportData.isRight() && BigDecimal.ZERO.equals(reportData.get().sumOf())) {
            return emptyReportData(reportId);
        }

        return Either.right(null);
    }

    private static Either<Problem, Void> emptyReportData(String reportId) {
        return Either.left(Problem.builder()
                .withTitle(Constants.EMPTY_REPORT_DATA)
                .withDetail(Constants.REPORT_IS_EMPTY)
                .withStatus(Status.BAD_REQUEST)
                .with(Constants.REPORT_ID, reportId)
                .build());
    }

    public Either<Problem, Void> canPublish(ReportEntity reportEntity) {
        // Validate profitForTheYear consistency between IncomeStatementData and BalanceSheetData
        ReportType relatedReportType = reportEntity.getType().equals(INCOME_STATEMENT) ? BALANCE_SHEET : INCOME_STATEMENT;
        String relatedReportId = Report.idControl(reportEntity.getOrganisation().getId(), relatedReportType, reportEntity.getIntervalType(), reportEntity.getYear(), reportEntity.getPeriod());
        Optional<ReportEntity> relatedReportM = reportRepository.findLatestByIdControl(reportEntity.getOrganisation().getId(), relatedReportId);

        if (!reportEntity.isValid()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.INVALID_REPORT_DATA)
                    .withDetail(Constants.REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED)
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_ID, reportEntity.getReportId())
                    .build());
        }

        if (relatedReportM.isPresent()) {
            ReportEntity relatedReport = relatedReportM.orElseThrow();
            if (!relatedReport.isValid()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_REPORT_DATA)
                        .withDetail(Constants.REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED)
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportEntity.getReportId())
                        .build());
            }

            BigDecimal relatedProfit = relatedReport.getType().equals(INCOME_STATEMENT)
                    ? relatedReport.getIncomeStatementReportData().flatMap(IncomeStatementData::getProfitForTheYear).orElse(BigDecimal.ZERO)
                    : relatedReport.getBalanceSheetReportData().flatMap(BalanceSheetData::getCapital).flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

            BigDecimal newProfit = reportEntity.getType().equals(INCOME_STATEMENT)
                    ? reportEntity.getIncomeStatementReportData().flatMap(IncomeStatementData::getProfitForTheYear).orElse(BigDecimal.ZERO)
                    : reportEntity.getBalanceSheetReportData().flatMap(BalanceSheetData::getCapital).flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

            if (0 != newProfit.compareTo(relatedProfit)) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.PROFIT_FOR_THE_YEAR_MISMATCH)
                        .withDetail(Constants.PROFIT_FOR_THE_YEAR_DOES_NOT_MATCH_THE_RELATED_REPORT_S_S.formatted(newProfit, relatedProfit))
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportEntity.getReportId())
                        .build());
            }
        }
        // validate against generated report
        ReportGenerateRequest reportGenerateRequest = new ReportGenerateRequest(reportEntity.getType(), reportEntity.getIntervalType(), reportEntity.getYear(), reportEntity.getPeriod().orElse((short) 1), false);
        reportGenerateRequest.setOrganisationId(reportEntity.getOrganisation().getId());
        Either<Problem, ReportEntity> generatedReportE = reportGenerate(reportGenerateRequest);
        if (generatedReportE.isRight()) {
            boolean generatedReportsMatch = true;
            if (reportEntity.getType() == BALANCE_SHEET) {
                generatedReportsMatch = BalanceSheetMatcher.matches(generatedReportE.get().getBalanceSheetReportData(), reportEntity.getBalanceSheetReportData());
            } else if (reportEntity.getType() == INCOME_STATEMENT) {
                generatedReportsMatch = IncomeStatementMatcher.matches(generatedReportE.get().getIncomeStatementReportData(), reportEntity.getIncomeStatementReportData());
            }
            if(!generatedReportsMatch) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.REPORT_DATA_MISMATCH)
                        .withDetail(Constants.REPORT_DATA_DOES_NOT_MATCH_GENERATED_REPORT)
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_ID, reportEntity.getReportId())
                        .build());
            }
        } // we ignore the left case since it means there is no other report
        return Either.right(null);

    }

    public Set<ReportEntity> findReportsInDateRange(String organisationId,
                                                    ReportType reportType,
                                                    Optional<LocalDate> startDateO, Optional<LocalDate> endDateO) {
        LocalDate startDate = startDateO.orElse(LocalDate.EPOCH);
        LocalDate endDate = endDateO.orElse(LocalDate.now(clock));
        Set<ReportEntity> reportEntities = reportRepository.findByTypeAndWithinYearRange(organisationId, reportType, startDate.getYear(), endDate.getYear());

        // filtering by dates
        reportEntities = reportEntities.stream().filter(reportEntity -> {
            LocalDate reportStartDate = getReportStartDate(reportEntity.getIntervalType(), reportEntity.getPeriod().orElse((short) 0), reportEntity.getYear());
            LocalDate reportEndDate = getReportEndDate(reportEntity.getIntervalType(), reportStartDate);
            return reportStartDate.plusDays(1).isAfter(startDate) && reportEndDate.minusDays(1).isBefore(endDate);
        }).collect(Collectors.toSet());
        // sorting by Year, Quarter, Month
        List<ReportEntity> sortedEntities = reportEntities.stream().sorted((o1, o2) -> {
            if (!Objects.equals(o1.getYear(), o2.getYear())) {
                return Integer.compare(o1.getYear(), o2.getYear());
            }
            if (o1.getIntervalType() != o2.getIntervalType()) {
                return Integer.compare(o1.getIntervalType().ordinal(), o2.getIntervalType().ordinal());
            }
            return Integer.compare(o1.getPeriod().orElse((short) 1), o2.getPeriod().orElse((short) 1));
        }).toList();

        // filtering if there is bigger interval already included means if this report is for jan'24 and there is a report for Q1'24, we don't need the january one
        return sortedEntities.stream().filter(reportEntity -> {
            // if the report is already a year, we don't need to check anything we just need to check if there is already a report for the same year with a higher version
            if (reportEntity.getIntervalType() == YEAR) {
                return sortedEntities.stream().filter(r -> r.getYear().equals(reportEntity.getYear()) && r.getVer() > reportEntity.getVer()).findAny().isEmpty();
            }
            // for quarters we need to check if there is a report for the same year or quarter with a higher version
            if (reportEntity.getIntervalType() == QUARTER) {
                if (reportEntity.getPeriod().isEmpty()) {
                    return false;
                }
                return sortedEntities.stream().filter(r -> r.getIntervalType() == YEAR && r.getYear().equals(reportEntity.getYear())).findAny().isEmpty()
                        && sortedEntities.stream().filter(r ->
                        r.getIntervalType() == QUARTER
                                && r.getPeriod().isPresent()
                                && r.getPeriod().get().equals(reportEntity.getPeriod().get())
                                && r.getVer() > reportEntity.getVer()).findAny().isEmpty();
            }
            // For months we need to check if there is a report for the same year or quarter or a month report with a higer version
            if (reportEntity.getIntervalType() == MONTH) {
                if (reportEntity.getPeriod().isEmpty()) {
                    return false;
                }
                int quarter = (reportEntity.getPeriod().get() - 1) / 3 + 1;
                return sortedEntities.stream().filter(r -> r.getIntervalType() == YEAR && r.getYear().equals(reportEntity.getYear())).findAny().isEmpty()
                        && sortedEntities.stream().filter(r ->
                        r.getIntervalType() == QUARTER
                                && r.getPeriod().isPresent()
                                && r.getPeriod().get() == quarter).findAny().isEmpty() &&
                        sortedEntities.stream().filter(r ->
                                r.getIntervalType() == MONTH
                                        && r.getPeriod().isPresent()
                                        && r.getPeriod().get().equals(reportEntity.getPeriod().get())
                                        && r.getVer() > reportEntity.getVer()).findAny().isEmpty();
            }
            return true;
        }).collect(Collectors.toSet());
    }

    private ReportEntity newReport() {
        ReportEntity report = new ReportEntity();
        report.setVer(1L);
        return report;
    }

    private ReportEntity newReport(long ver) {
        ReportEntity report = new ReportEntity();
        report.setVer(ver + 1L);
        return report;
    }

    public Either<Problem, ReportEntity> reportGenerate(@Valid ReportGenerateRequest reportGenerateRequest) {
        LocalDate startDate = getReportStartDate(reportGenerateRequest.getIntervalType(), reportGenerateRequest.getPeriod(), reportGenerateRequest.getYear());
        LocalDate endDate = getReportEndDate(reportGenerateRequest.getIntervalType(), getReportStartDate(reportGenerateRequest.getIntervalType(), reportGenerateRequest.getPeriod(), reportGenerateRequest.getYear()));

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setYear(reportGenerateRequest.getYear());
        reportEntity.setPeriod(Optional.of(reportGenerateRequest.getPeriod()));
        reportEntity.setIntervalType(reportGenerateRequest.getIntervalType());
        reportEntity.setMode(USER);
        reportEntity.setDate(LocalDate.now(clock));
        reportEntity.setOrganisation(Organisation.builder().id(reportGenerateRequest.getOrganisationId()).build());
        reportEntity.setType(reportGenerateRequest.getReportType());


        Optional<ReportTypeEntity> optionalReportSetupEntity = reportTypeRepository.findByOrganisationAndReportName(reportGenerateRequest.getOrganisationId(), reportGenerateRequest.getReportType().name());

        if (optionalReportSetupEntity.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_SETUP_NOT_FOUND)
                    .withDetail(String.format(Constants.REPORT_SETUP_FOR_S_NOT_FOUND, reportGenerateRequest.getReportType().name()))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_TYPE, reportGenerateRequest.getReportType().name())
                    .build());
        }
        ReportTypeEntity reportTypeEntity = optionalReportSetupEntity.get();
        switch (reportGenerateRequest.getReportType()) {
            case BALANCE_SHEET -> {
                BalanceSheetData balanceSheetData = new BalanceSheetData();
                fillReportData(balanceSheetData, reportTypeEntity, startDate, endDate,
                        reportGenerateRequest.isPreview());
                reportEntity.setBalanceSheetReportData(Optional.of(balanceSheetData));
            }
            case INCOME_STATEMENT -> {
                IncomeStatementData incomeStatementData = new IncomeStatementData();
                fillReportData(incomeStatementData, reportTypeEntity, startDate, endDate, reportGenerateRequest.isPreview());
                reportEntity.setIncomeStatementReportData(Optional.of(incomeStatementData));
            }
            default -> {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_REPORT_TYPE)
                        .withDetail(String.format(Constants.REPORT_TYPE_IS_NOT_VALID_EXPECTED_BALANCE_SHEET_OR_INCOME_STATEMENT_BUT_GOT_S, reportGenerateRequest.getReportType()))
                        .withStatus(Status.BAD_REQUEST)
                        .with(Constants.REPORT_TYPE, reportGenerateRequest.getReportType())
                        .build());
            }
        }

        return Either.right(reportEntity);
    }

    private void fillReportData(Object reportData, ReportTypeEntity reportTypeEntity, LocalDate startDate, LocalDate endDate, boolean preview) {
        // if we can solve it differently it would be better
        Set<ReportTypeFieldEntity> topLevelFields = reportTypeEntity.getFields().stream().filter(field -> field.getParent() == null).collect(Collectors.toSet());
        topLevelFields.forEach(reportTypeFieldEntity -> {
            fillObjectRecursively(reportData, reportTypeFieldEntity, startDate, endDate, preview);
        });
    }

    /**
     * This method fills the report data object recursively based on the provided field and date range.
     * It will check if the field has child fields and if so, it will create a nested object for each child field.
     * If the field has no child fields, it will calculate the total amount based on the mapping types and set the value in the report data object.
     * NOTE: Currently it is only possible to have a value at the bottom level (No children) of the report data object.
     */
    private void fillObjectRecursively(Object reportData, ReportTypeFieldEntity field, LocalDate startDate, LocalDate endDate, boolean preview) {
        if (field.getChildFields().isEmpty()) {
            if (field.getMappingTypes().isEmpty() && field.getMappingReportTypes().isEmpty()) {
                log.debug("Field %s has no mapping type, skipping...".formatted(field.getName()));
                return;
            }

            Optional<LocalDate> startSearchDate = Optional.of(startDate);
            BigDecimal totalAmount = BigDecimal.ZERO;

            if (field.isAccumulatedYearly()) {
                startSearchDate = Optional.of(LocalDate.of(startDate.getYear(), 1, 1));
            }
            if (field.isAccumulated()) {
                startSearchDate = Optional.of(LocalDate.EPOCH);
            }
            if (field.isAccumulatedPreviousYear()) {
                if (!field.isAccumulated()) {
                    startSearchDate = Optional.of(LocalDate.of(startDate.getYear() - 1, 1, 1));
                }

                endDate = LocalDate.of(startDate.getYear() - 1, 12, 31);
            }

            totalAmount = addValuesFromTransactionItems(field, endDate, totalAmount, startSearchDate, preview);
            totalAmount = addValuesFromReportFields(field, endDate, totalAmount, startSearchDate);
            if (field.isNegate()) {
                totalAmount = totalAmount.negate();
            }
            // Set value dynamically in reportData
            setFieldValue(reportData, field.getName(), totalAmount);
        } else {
            LocalDate finalEndDate = endDate;
            field.getChildFields().forEach(subField -> {
                Object subFieldObject = getOrCreateNestedObject(reportData, field.getName());
                fillObjectRecursively(subFieldObject, subField, startDate, finalEndDate, preview);
            });
        }
    }

    private BigDecimal addValuesFromReportFields(ReportTypeFieldEntity field, LocalDate endDate, BigDecimal totalAmount, Optional<LocalDate> startSearchDate) {
        List<ReportTypeFieldEntity> mappingReportTypes = field.getMappingReportTypes();
        for (ReportTypeFieldEntity mappedTypeField : mappingReportTypes) {

            // getting all reports for the selected years and filtering them to see if the interval is within the selected date range
            // I'm doing it in code currently to keep it as simple as possible, since the logic is already complex
            ReportType reportType = ReportType.valueOf(mappedTypeField.getReport().getName());

            Set<ReportEntity> reportEntities = findReportsInDateRange(mappedTypeField.getReport().getOrganisationId(), reportType, startSearchDate, Optional.of(endDate));

            // getting the report data from the report entity
            for (ReportEntity reportEntity : reportEntities) {
                // Currently this is only non-generic part in this method
                if (reportEntity.getType() == INCOME_STATEMENT) {
                    IncomeStatementData incomeStatementData = reportEntity.getIncomeStatementReportData().orElseThrow();
                    totalAmount = totalAmount.add(getFieldValueFromReportData(incomeStatementData, mappedTypeField));
                } else if (reportEntity.getType() == BALANCE_SHEET) {
                    BalanceSheetData balanceSheetData = reportEntity.getBalanceSheetReportData().orElseThrow();
                    totalAmount = totalAmount.add(getFieldValueFromReportData(balanceSheetData, mappedTypeField));
                }
            }
        }
        return totalAmount;
    }

    /**
     * This function is retrieving the data from the object report data based on the mappedTypeField
     * It could be the case that the mappedTypeField has a parent, so we are going to iterate through the parents
     * and get the field value from the report data object
     */
    private BigDecimal getFieldValueFromReportData(Object reportData, ReportTypeFieldEntity mappedTypeField) {
        List<String> fields = new ArrayList<>();
        fields.addFirst(mappedTypeField.getName());
        while (mappedTypeField.getParent() != null) {
            mappedTypeField = mappedTypeField.getParent();
            fields.addFirst(mappedTypeField.getName());
        }
        // get the field value from the report data object
        Object currentObject = reportData;
        for (int i = 0; i < fields.size(); i++) {
            String fieldName = fields.get(i);
            try {
                Field field = currentObject.getClass().getDeclaredField(toCamelCase(fieldName));
                field.setAccessible(true);
                currentObject = field.get(currentObject);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.error("Field %s not found in object %s".formatted(fieldName, currentObject.getClass().getName()));
                currentObject = null;
                break;
            }
        }
        // check if object is null
        if (currentObject == null) {
            return BigDecimal.ZERO;
        } else if (currentObject instanceof BigDecimal bigDecimalObject) {
            return bigDecimalObject;
        } else if (currentObject instanceof Number numberObject) {
            return BigDecimal.valueOf((numberObject).doubleValue());
        } else if (currentObject instanceof String stringObject) {
            return new BigDecimal(stringObject);
        } else {
            log.error("Field %s is not a number, returning 0".formatted(mappedTypeField.getName()));
            return BigDecimal.ZERO;
        }
    }

    /**
     * This method adds values from transaction items to the total amount based on the provided field and date range.
     * It will find all ChartOfAccounts, that are mapped to the specific field via the subtypes from the chartOfAccount.
     * Then it will find all transaction items that are related to these ChartOfAccounts and fall within the specified date range.
     * Sum them up and return these.
     */
    private BigDecimal addValuesFromTransactionItems(ReportTypeFieldEntity field, LocalDate endDate, BigDecimal totalAmount, Optional<LocalDate> startSearchDate, boolean preview) {
        // Finding all ChartOfAccounts that are mapped to the specific field via the subtypes from the chartOfAccount
        Set<ChartOfAccount> allByOrganisationIdSubTypeIds = chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(field.getMappingTypes().stream().map(ChartOfAccountSubType::getId).toList());

        // adding Opening Balance if the startDate is before the OpeningBalance Date
        totalAmount = totalAmount.add(allByOrganisationIdSubTypeIds.stream().map(organisationChartOfAccount -> Objects.isNull(organisationChartOfAccount.getOpeningBalance()) ?
                        BigDecimal.ZERO :
                        // adding one day since we want to have a isAfter or Equal to the start date
                        organisationChartOfAccount.getOpeningBalance().getDate().plusDays(1).isAfter(startSearchDate.get())
                                && organisationChartOfAccount.getOpeningBalance().getDate().minusDays(1).isBefore(endDate) ?
                                Optional.ofNullable(organisationChartOfAccount.getOpeningBalance().getBalanceType()).orElse(OperationType.DEBIT) == OperationType.DEBIT ?
                                        Optional.ofNullable(organisationChartOfAccount.getOpeningBalance().getBalanceLCY()).orElse(BigDecimal.ZERO) :
                                        Optional.ofNullable(organisationChartOfAccount.getOpeningBalance().getBalanceLCY()).orElse(BigDecimal.ZERO).negate()
                                : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        // Finding all transaction items that are related to these ChartOfAccounts and fall within the specified date range
        List<TransactionItemEntity> transactionItemsByAccountCodeAndDateRange;
        if(preview) {
            transactionItemsByAccountCodeAndDateRange =
                    transactionItemRepository
                            .findPreviewTransactionItemsByAccountCodeAndDateRange(
                                    allByOrganisationIdSubTypeIds.stream()
                                            .map(organisationChartOfAccount -> Objects
                                                    .requireNonNull(
                                                            organisationChartOfAccount.getId())
                                                    .getCustomerCode())
                                            .toList(),
                                    startSearchDate.orElse(LocalDate.EPOCH), endDate);
        } else {
            transactionItemsByAccountCodeAndDateRange = transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(
                    allByOrganisationIdSubTypeIds.stream().map(organisationChartOfAccount -> Objects.requireNonNull(organisationChartOfAccount.getId()).getCustomerCode()).toList(),
                    startSearchDate.orElse(LocalDate.EPOCH), endDate);
        }
        Map<String, ChartOfAccount> selfMap = allByOrganisationIdSubTypeIds.stream().collect(Collectors.toMap(o -> o.getId().getCustomerCode(), organisationChartOfAccount -> organisationChartOfAccount));

        // Summing up the amounts
        totalAmount = totalAmount.add(transactionItemsByAccountCodeAndDateRange.stream().map(transactionItemEntity -> {
                    // Skipping invalid transaction Items
                    if (transactionItemEntity.getStatus() != TxItemValidationStatus.OK) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal amount = BigDecimal.ZERO;
                    // adding the value if it's debit and subtracting it if it's Credit
                    // Account is on Debit
                    if (transactionItemEntity.getAccountDebit().isPresent() && selfMap.containsKey(transactionItemEntity.getAccountDebit().get().getCode())) {
                        if (transactionItemEntity.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                            amount = amount.add(transactionItemEntity.getAmountLcy());
                        } else {
                            amount = amount.add(transactionItemEntity.getAmountLcy().negate());
                        }
                    }

                    if (transactionItemEntity.getAccountCredit().isPresent() && selfMap.containsKey(transactionItemEntity.getAccountCredit().get().getCode())) {
                        if (transactionItemEntity.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                            amount = amount.subtract(transactionItemEntity.getAmountLcy());
                        } else {
                            amount = amount.subtract(transactionItemEntity.getAmountLcy().negate());
                        }
                    }
                    return amount.stripTrailingZeros();
                }
        ).reduce(BigDecimal.ZERO, BigDecimal::add));
        return totalAmount;
    }

    /**
     * This method retrieves or creates a nested object within the given object based on the field name.
     * It uses reflection to access the field and instantiate it if it's null.
     *
     * @param object    The parent object containing the field.
     * @param fieldName The name of the field to retrieve or create.
     * @return The nested object.
     */
    private Object getOrCreateNestedObject(Object object, String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(toCamelCase(fieldName));
            field.setAccessible(true);

            Object value = field.get(object);
            if (value == null) {
                // Instantiate the nested field if null
                value = field.getType().getDeclaredConstructor().newInstance();
                field.set(object, value);
            }
            return value;
        } catch (NoSuchFieldException | IllegalAccessException | InstantiationException | NoSuchMethodException |
                 InvocationTargetException e) {
            throw new RuntimeException("Failed to get or create field: " + fieldName, e);
        }
    }

    /**
     * This method sets the value of a field in the given object using reflection.
     *
     * @param object    The object containing the field.
     * @param fieldName The name of the field to set.
     * @param value     The value to set in the field.
     */
    private void setFieldValue(Object object, String fieldName, Object value) {
        try {
            Field field = object.getClass().getDeclaredField(toCamelCase(fieldName));
            field.setAccessible(true);
            field.set(object, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    /**
     * This method retrieves the start date based on the interval type, period, and year.
     *
     * @param intervalType The interval type (MONTH, QUARTER, YEAR).
     * @param period       The period (month or quarter).
     * @param year         The year.
     * @return The start date.
     */
    public LocalDate getReportStartDate(IntervalType intervalType, int period, short year) {
        switch (intervalType) {
            case MONTH:
                return LocalDate.of(year, period, 1);
            case QUARTER:
                int month = (period - 1) * 3 + 1; // Convert quarter to starting month
                return LocalDate.of(year, month, 1);
            case YEAR:
                return LocalDate.of(year, 1, 1);
            default:
                throw new IllegalArgumentException("Unsupported IntervalType: " + intervalType);
        }
    }

    /**
     * This method retrieves the end date based on the interval type and start date.
     *
     * @param intervalType The interval type (MONTH, QUARTER, YEAR).
     * @param startDate    The start date.
     * @return The end date.
     */
    public LocalDate getReportEndDate(IntervalType intervalType, LocalDate startDate) {
        switch (intervalType) {
            case MONTH:
                return startDate.plusMonths(1).minusDays(1);
            case QUARTER:
                return startDate.plusMonths(3).minusDays(1);
            case YEAR:
                return startDate.plusYears(1).minusDays(1);
            default:
                throw new IllegalArgumentException("Unsupported IntervalType: " + intervalType);
        }
    }

    public Optional<ReportEntity> getMostRecentReport(Set<ReportEntity> reportEntities) {
        return reportEntities.stream()
                .max(Comparator.comparing(o ->
                        getReportEndDate(
                                o.getIntervalType(),
                                getReportStartDate(
                                        o.getIntervalType(),
                                        o.getPeriod().orElseThrow(), // handle Optional safely if needed
                                        o.getYear()
                                )
                        )
                ));
    }

    /**
     * This method converts a string to camel case format.
     *
     * @param input The input string to convert.
     * @return The camel case formatted string.
     */
    private String toCamelCase(String input) {
        String[] parts = input.toLowerCase().split("_");
        StringBuilder camelCaseString = new StringBuilder(parts[0]); // Keep the first word lowercase

        for (int i = 1; i < parts.length; i++) {
            camelCaseString.append(parts[i].substring(0, 1).toUpperCase()).append(parts[i].substring(1));
        }

        return camelCaseString.toString();
    }

    public Either<Problem, ReportEntity> reportReprocess(@Valid ReportReprocessRequest reportReprocessRequest) {
        Optional<ReportEntity> firstByOrganisationIdAndReportId = reportRepository.findFirstByOrganisationIdAndReportId(reportReprocessRequest.getOrganisationId(), reportReprocessRequest.getReportId());
        if (firstByOrganisationIdAndReportId.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(reportReprocessRequest.getReportId()))
                    .withStatus(Status.NOT_FOUND)
                    .with(Constants.REPORT_ID, reportReprocessRequest.getReportId())
                    .build());
        }
        ReportEntity reportEntity = firstByOrganisationIdAndReportId.get();
        if (reportEntity.getLedgerDispatchStatus() != LedgerDispatchStatus.NOT_DISPATCHED) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_ALREADY_DISPATCHED)
                    .withDetail(Constants.REPORT_WITH_ID_S_HAS_ALREADY_BEEN_DISPATCHED.formatted(reportReprocessRequest.getReportId()))
                    .withStatus(Status.BAD_REQUEST)
                    .with(Constants.REPORT_ID, reportReprocessRequest.getReportId())
                    .build());
        }
        Either<Problem, Void> isReportReadyToPublish = canPublish(reportEntity);
        reportEntity.setIsReadyToPublish(true);
        if (isReportReadyToPublish.isLeft()) {
            reportEntity.setIsReadyToPublish(false);
            reportEntity.setPublishError(PublishError.valueOf(isReportReadyToPublish.getLeft().getTitle()));
        }
        return Either.right(reportRepository.save(reportEntity));
    }
}
