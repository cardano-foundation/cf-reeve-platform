package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;


import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType.MONTH;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType.YEAR;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.USER;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.BALANCE_SHEET;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.INCOME_STATEMENT;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.PublicReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
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

    @Transactional
    public Either<Problem, ReportEntity> approveReportForLedgerDispatch(String reportId) {
        val reportM = reportRepository.findById(reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_NOT_FOUND")
                    .withDetail(STR."Report with ID \{reportId} does not exist.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("reportId", reportId)
                    .build());
        }
        val report = reportM.orElseThrow();
        val canI = canPublish(report);
        if (canI.isLeft()) {
            return Either.left(canI.getLeft());
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

        val orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with ID \{organisationId} does not exist.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("organisationId", organisationId)
                    .build());
        }
        val org = orgM.orElseThrow();

        val reportExample = new ReportEntity();
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

        var incomeStatementReportData = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(new BigDecimal("10000.90"))
                        .buildOfLongTermProvision(new BigDecimal("1000000.10"))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .costOfProvidingServices(new BigDecimal("500000.15"))
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
                        .incomeTaxExpense(new BigDecimal("1000.51"))
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

        val orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with ID \{organisationId} does not exist.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("organisationId", organisationId)
                    .build());
        }
        val org = orgM.orElseThrow();

        val reportExample = new ReportEntity();
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
                                .propertyPlantEquipment(new BigDecimal("265306.12"))
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
                                .otherCurrentLiabilities(new BigDecimal("10000.00"))
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
                    .withTitle("INVALID_REPORT")
                    .withDetail(STR."Report is not valid since it didn't pass through business checks.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("reportId", reportExample.getReportId())
                    .with("reportType", reportExample.getType())
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
        if(reportType == BALANCE_SHEET && createReportView.getBalanceSheetData().isEmpty() ||
        reportType == INCOME_STATEMENT && createReportView.getIncomeStatementData().isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("INVALID_REPORT_TYPE")
                    .withDetail(STR."Report type is not valid. Expected BALANCE_SHEET but got \{reportType}.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("reportType", reportType)
                    .build());
        }
        String organisationId = createReportView.getOrganisationId();
        val orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with ID \{organisationId} does not exist.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("organisationId", organisationId)
                    .build());
        }
        val org = orgM.orElseThrow();

        val reportEntityE = exist(organisationId, reportType, intervalType, year, period);
        ReportEntity reportEntity = reportEntityE.fold(problem -> {
            // question: is it safe to assume that problem will always be because it already exists?

            return newReport();
        }, success -> {
            if (success.getLedgerDispatchApproved()) {
                return newReport();
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

        if(reportType == BALANCE_SHEET) {
            reportEntity.setBalanceSheetReportData(createReportView.getBalanceSheetData());
        } else if(reportType == INCOME_STATEMENT) {
            reportEntity.setIncomeStatementReportData(createReportView.getIncomeStatementData());
        }

        val result = reportRepository.save(reportEntity);

        log.info(reportType.name() + "::Report saved successfully: {}", result.getReportId());

        return Either.right(result);
    }

    public Set<ReportEntity> findAllByOrgId(String organisationId) {
        return reportRepository.findAllByOrganisationId(organisationId);
    }

    public Set<ReportEntity> findAllByTypeAndPeriod(String organistionId,ReportType reportType, IntervalType intervalType, short year, short period) {
        return publicReportRepository.findAllByTypeAndPeriod(organistionId,reportType, intervalType, year, period);
    }

    public Either<Problem, ReportEntity> exist(String organisationId, ReportType reportType, IntervalType intervalType, short year, short period) {
        String reportId = Report.idControl(organisationId, reportType, intervalType, year, Optional.of(period));
        val reportM = reportRepository.findLatestByIdControl(organisationId, reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_NOT_FOUND")
                    .withDetail(STR."Report with ID \{reportId} does not exist.")
                    .withStatus(Status.NOT_FOUND)
                    .with("reportId", reportId)
                    .build());
        }

        val report = reportM.orElseThrow();

        return Either.right(report);
    }

    public Either<Problem, Boolean> isReportValid(String reportId) {
        val reportM = reportRepository.findById(reportId);

        if (reportM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_NOT_FOUND")
                    .withDetail(STR."Report with ID \{reportId} does not exist.")
                    .withStatus(Status.NOT_FOUND)
                    .with("reportId", reportId)
                    .build());
        }

        val report = reportM.orElseThrow();

        return Either.right(report.isValid());
    }

    public Either<Problem, Void> store(String organisationId,
                                       IntervalType intervalType,
                                       short year,
                                       Integer ver,
                                       Optional<Short> period,
                                       Either<IncomeStatementData, BalanceSheetData> reportData) {
        val orgM = organisationPublicApi.findByOrganisationId(organisationId);
        if (orgM.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation with ID \{organisationId} does not exist.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("organisationId", organisationId)
                    .build());
        }
        val org = orgM.orElseThrow();

        val reportType = reportData.isLeft() ? INCOME_STATEMENT : BALANCE_SHEET;

        val reportId = Report.id(organisationId, reportType, intervalType, year, ver, period);
        val idControl = Report.idControl(organisationId, reportType, intervalType, year, period);
        val existingReportM = reportRepository.findById(reportId);

        var reportEntity = new ReportEntity();
        if (existingReportM.isPresent()) {
            reportEntity = existingReportM.orElseThrow();
            // Prevent overwriting approved reports
            if (reportEntity.getLedgerDispatchApproved()) {
                return Either.left(Problem.builder()
                        .withTitle("REPORT_ALREADY_APPROVED")
                        .withDetail(STR."Report with ID \{reportId} has already been approved for ledger dispatch.")
                        .withStatus(Status.BAD_REQUEST)
                        .with("reportId", reportId)
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
        val relatedReportType = reportData.isLeft() ? BALANCE_SHEET : INCOME_STATEMENT;
        val relatedReportId = Report.id(organisationId, relatedReportType, intervalType, year, ver, period);
        val relatedReportM = reportRepository.findById(relatedReportId);

        if (relatedReportM.isPresent()) {
            val relatedReport = relatedReportM.orElseThrow();
            val relatedProfit = relatedReport.getIncomeStatementReportData()
                    .map(IncomeStatementData::getProfitForTheYear)
                    .or(() -> relatedReport.getBalanceSheetReportData().flatMap(bsd -> bsd.getCapital().map(BalanceSheetData.Capital::getProfitForTheYear)));

            if (relatedProfit.isPresent()) {
                BigDecimal newProfit = reportData.isLeft()
                        ? reportData.getLeft().getProfitForTheYear().orElse(BigDecimal.ZERO)
                        : reportData.get().getCapital().flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

                if (!newProfit.equals(relatedProfit.get().orElse(BigDecimal.ZERO))) {
                    return Either.left(Problem.builder()
                            .withTitle("PROFIT_FOR_THE_YEAR_MISMATCH")
                            .withDetail(STR."Profit for the year does not match the related report.")
                            .withStatus(Status.BAD_REQUEST)
                            .with("reportId", reportId)
                            .build());
                }
            }
        }

        val emptyCheckE = checkIfEmpty(reportId, reportData);
        if (emptyCheckE.isLeft()) {
            return emptyCheckE;
        }

        if (reportData.isLeft()) {
            val reportDataLeft = reportData.getLeft();

            if (!reportDataLeft.isValid()) {
                return Either.left(Problem.builder()
                        .withTitle("INVALID_REPORT_DATA")
                        .withDetail(STR."Income Statement report data is not valid. Business Checks failed.")
                        .withStatus(Status.BAD_REQUEST)
                        .with("reportId", reportId)
                        .build());
            }

            reportEntity.setIncomeStatementReportData(Optional.of(reportData.getLeft()));
        } else {
            if (!reportData.get().isValid()) {
                return Either.left(Problem.builder()
                        .withTitle("INVALID_REPORT_DATA")
                        .withDetail(STR."Balance Sheet report data is not valid. Business Checks failed.")
                        .withStatus(Status.BAD_REQUEST)
                        .with("reportId", reportId)
                        .build());
            }

            reportEntity.setBalanceSheetReportData(Optional.of(reportData.get()));
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
                .withTitle("EMPTY_REPORT_DATA")
                .withDetail(STR."Report is empty.")
                .withStatus(Status.BAD_REQUEST)
                .with("reportId", reportId)
                .build());
    }

    public Either<Problem, Boolean> canPublish(ReportEntity reportEntity) {

        // Validate profitForTheYear consistency between IncomeStatementData and BalanceSheetData
        val relatedReportType = reportEntity.getType().equals(INCOME_STATEMENT) ? BALANCE_SHEET : INCOME_STATEMENT;
        val relatedReportId = Report.idControl(reportEntity.getOrganisation().getId(), relatedReportType, reportEntity.getIntervalType(), reportEntity.getYear(), reportEntity.getPeriod());
        val relatedReportM = reportRepository.findLatestByIdControl(reportEntity.getOrganisation().getId(), relatedReportId);

        if (!reportEntity.isValid()) {
            return Either.left(Problem.builder()
                    .withTitle("INVALID_REPORT_DATA")
                    .withDetail(STR."Report data is not valid. Business Checks failed.")
                    .withStatus(Status.BAD_REQUEST)
                    .with("reportId", reportEntity.getReportId())
                    .build());
        }

        if (relatedReportM.isPresent()) {
            val relatedReport = relatedReportM.orElseThrow();
            if (!relatedReport.isValid()) {
                return Either.left(Problem.builder()
                        .withTitle("INVALID_REPORT_DATA")
                        .withDetail(STR."Report data is not valid. Business Checks failed.")
                        .withStatus(Status.BAD_REQUEST)
                        .with("reportId", reportEntity.getReportId())
                        .build());
            }

            val relatedProfit = relatedReport.getType().equals(INCOME_STATEMENT)
                    ? relatedReport.getIncomeStatementReportData().flatMap(IncomeStatementData::getProfitForTheYear).orElse(BigDecimal.ZERO)
                    : relatedReport.getBalanceSheetReportData().flatMap(BalanceSheetData::getCapital).flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

            BigDecimal newProfit = reportEntity.getType().equals(INCOME_STATEMENT)
                    ? reportEntity.getIncomeStatementReportData().flatMap(IncomeStatementData::getProfitForTheYear).orElse(BigDecimal.ZERO)
                    : reportEntity.getBalanceSheetReportData().flatMap(BalanceSheetData::getCapital).flatMap(BalanceSheetData.Capital::getProfitForTheYear).orElse(BigDecimal.ZERO);

            if (0 != newProfit.compareTo(relatedProfit)) {
                return Either.left(Problem.builder()
                        .withTitle("PROFIT_FOR_THE_YEAR_MISMATCH")
                        .withDetail(STR."Profit for the year does not match the related report. \{newProfit} != \{relatedProfit}")
                        .withStatus(Status.BAD_REQUEST)
                        .with("reportId", reportEntity.getReportId())
                        .build());
            }
        }
        return Either.right(true);
    }

    private ReportEntity newReport() {
        ReportEntity report = new ReportEntity();
        report.setVer(clock.millis());
        return report;
    }
}
