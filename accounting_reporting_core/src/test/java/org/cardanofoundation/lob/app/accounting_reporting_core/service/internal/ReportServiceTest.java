package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.BALANCE_SHEET;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportType.INCOME_STATEMENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import io.vavr.control.Either;
import org.mockito.*;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.IntervalType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.Report;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.ReportEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ReportRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests.ReportGenerateRequest;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;

class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private OrganisationPublicApi organisationPublicApi;
    @Mock
    private ReportTypeRepository reportTypeRepository;
    @Mock
    private ChartOfAccountRepository chartOfAccountRepository;
    @Mock
    private TransactionItemRepository transactionItemRepository;

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private ReportService reportService;

    private static final String REPORT_ID = "test-report-id";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void approveReportForLedgerDispatch_whenReportExists_shouldNotSetLedgerDispatchApproved() {
        // Arrange
        ReportEntity reportEntity = new ReportEntity();
        ReportEntity reportEntityVerify = new ReportEntity();

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id("theOrgId")
                .name("Org Name")
                .countryCode("US")
                .currencyId("ISO_4217:CHF")
                .taxIdNumber("12345").build();


        reportEntity.setReportId(REPORT_ID);
        reportEntity.setType(INCOME_STATEMENT);
        reportEntity.setOrganisation(organisation);
        reportEntity.setYear((short) 2023);


        reportEntityVerify.setReportId("another");
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportEntity));
        when(reportRepository.findLatestByIdControl(Mockito.anyString(), Mockito.anyString())).thenReturn(Optional.of(reportEntityVerify));
        // Act
        Either<Problem, ReportEntity> result = reportService.approveReportForLedgerDispatch(REPORT_ID);

        // Assert
        assertThat(result.isRight()).isFalse();
        assertThat(reportEntity.getLedgerDispatchApproved()).isFalse();
        verify(reportRepository,never()).save(reportEntity);
    }

    @Test
    void approveReportForLedgerDispatch_whenReportDoesNotExist_shouldReturnProblem() {
        // Arrange
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        // Act
        Either<Problem, ReportEntity> result = reportService.approveReportForLedgerDispatch(REPORT_ID);

        // Assert
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("REPORT_NOT_FOUND");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void exists_whenReportExists_shouldReturnTrue() {
        // Arrange
        when(reportRepository.existsById(REPORT_ID)).thenReturn(true);

        // Act
        boolean result = reportService.exists(REPORT_ID);

        // Assert
        assertThat(result).isTrue();
        verify(reportRepository).existsById(REPORT_ID);
    }

    @Test
    void exists_whenReportDoesNotExist_shouldReturnFalse() {
        // Arrange
        when(reportRepository.existsById(REPORT_ID)).thenReturn(false);

        // Act
        boolean result = reportService.exists(REPORT_ID);

        // Assert
        assertThat(result).isFalse();
        verify(reportRepository).existsById(REPORT_ID);
    }

    @Test
    void findById_whenReportExists_shouldReturnReport() {
        // Arrange
        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setReportId(REPORT_ID);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportEntity));

        // Act
        Optional<ReportEntity> result = reportService.findById(REPORT_ID);

        // Assert
        assertThat(result).isPresent().contains(reportEntity);
        verify(reportRepository).findById(REPORT_ID);
    }

    @Test
    void findById_whenReportDoesNotExist_shouldReturnEmpty() {
        // Arrange
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        // Act
        Optional<ReportEntity> result = reportService.findById(REPORT_ID);

        // Assert
        assertThat(result).isNotPresent();
        verify(reportRepository).findById(REPORT_ID);
    }

    @Test
    void isReportValid_whenReportExistsAndIsValid_shouldReturnTrue() {
        // Arrange
        ReportEntity reportEntity = mock(ReportEntity.class);
        when(reportEntity.isValid()).thenReturn(true);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportEntity));

        // Act
        Either<Problem, Boolean> result = reportService.isReportValid(REPORT_ID);

        // Assert
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isTrue();
        verify(reportEntity).isValid();
    }

    @Test
    void isReportValid_whenReportExistsAndIsNotValid_shouldReturnFalse() {
        // Arrange
        ReportEntity reportEntity = mock(ReportEntity.class);
        when(reportEntity.isValid()).thenReturn(false);
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(reportEntity));

        // Act
        Either<Problem, Boolean> result = reportService.isReportValid(REPORT_ID);

        // Assert
        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isFalse();
        verify(reportEntity).isValid();
    }

    @Test
    void isReportValid_whenReportDoesNotExist_shouldReturnProblem() {
        // Arrange
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.empty());

        // Act
        Either<Problem, Boolean> result = reportService.isReportValid(REPORT_ID);

        // Assert
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("REPORT_NOT_FOUND");
        verify(reportRepository).findById(REPORT_ID);
    }

    @Test
    void store_whenBalanceSheetData_shouldStoreSuccessfully() {
        // Arrange
        val organisationId = "org-1";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;
        val periodM = Optional.<Short>empty();

        val balanceSheetData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder().build())
                .liabilities(BalanceSheetData.Liabilities.builder().build())
                .capital(BalanceSheetData.Capital.builder().build())
                .build();

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("USD");
        organisation.setCurrencyId("ISO_4217:CHF");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("EMPTY_REPORT_DATA");
    }

    @Test
    void store_whenIncomeStatementDataIsEmpty_shouldReturnProblem() {
        // Arrange
        val organisationId = "org-2";
        val intervalType = IntervalType.MONTH;
        short year = 2023;
        val periodM = Optional.of((short) 3);
        val ver = 1;

        val emptyIncomeStatementData = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder().build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder().build())
                .financialIncome(IncomeStatementData.FinancialIncome.builder().build())
                .extraordinaryIncome(IncomeStatementData.ExtraordinaryIncome.builder().build())
                .taxExpenses(IncomeStatementData.TaxExpenses.builder().build())
                .operatingExpenses(IncomeStatementData.OperatingExpenses.builder().build())
                .build();

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>left(emptyIncomeStatementData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("EMPTY_REPORT_DATA");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void store_whenBalanceSheetDataIsValid_shouldStoreSuccessfully() {
        // Arrange
        val organisationId = "org-3";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val periodM = Optional.<Short>empty();
        val ver = 1;

        val balanceSheetReportData = BalanceSheetData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isRight()).isTrue();
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void store_whenBalanceSheetIsValid_shouldStoreSuccessfully() {
        // Arrange
        val organisationId = "org-3";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;

        val periodM = Optional.<Short>empty();

        val balanceSheetReportData = BalanceSheetData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isRight()).isTrue();
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void store_whenBalanceSheetIsInvalid_shouldReturnProblem() {
        // Arrange
        val organisationId = "org-3";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;

        val periodM = Optional.<Short>empty();

        // Invalid balance sheet data: Assets ≠ Liabilities + Capital
        val balanceSheetReportData = BalanceSheetData.builder()
                .assets(BalanceSheetData.Assets.builder()
                        .nonCurrentAssets(BalanceSheetData.Assets.NonCurrentAssets.builder()
                                .tangibleAssets(new BigDecimal("100000.00"))
                                .intangibleAssets(new BigDecimal("50000.00"))
                                .investments(new BigDecimal("25000.00"))
                                .financialAssets(new BigDecimal("30000.00"))
                                .build())
                        .currentAssets(BalanceSheetData.Assets.CurrentAssets.builder()
                                .prepaymentsAndOtherShortTermAssets(new BigDecimal("15000.00"))
                                .otherReceivables(new BigDecimal("20000.00"))
                                .cryptoAssets(new BigDecimal("40000.00"))
                                .cashAndCashEquivalents(new BigDecimal("35000.00"))
                                .build())
                        .build())
                .liabilities(BalanceSheetData.Liabilities.builder()
                        .nonCurrentLiabilities(BalanceSheetData.Liabilities.NonCurrentLiabilities.builder()
                                .provisions(new BigDecimal("30000.00"))
                                .build())
                        .currentLiabilities(BalanceSheetData.Liabilities.CurrentLiabilities.builder()
                                .tradeAccountsPayables(new BigDecimal("15000.00"))
                                .otherShortTermLiabilities(new BigDecimal("10000.00"))
                                .accrualsAndShortTermProvisions(new BigDecimal("5000.00"))
                                .build())
                        .build())
                .capital(BalanceSheetData.Capital.builder()
                        .capital(new BigDecimal("150000.00"))
                        .profitForTheYear(new BigDecimal("50000.00"))
                        .resultsCarriedForward(new BigDecimal("20001.00"))
                        .build())
                .build();

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("INVALID_REPORT_DATA");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void store_whenIncomeStatementIsValid_shouldStoreSuccessfully() {
        // Arrange
        val organisationId = "org-3";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;
        val periodM = Optional.<Short>empty();

        var incomeStatementReportData = IncomeStatementData.builder()
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


        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>left(incomeStatementReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.empty());

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isRight()).isTrue();
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void store_whenReportIsDispatched_shouldNotAllowOverwritingForIncomeStatement() {
        // Arrange
        val organisationId = "org-4";
        val intervalType = IntervalType.MONTH;
        short year = 2023;
        val ver = 1;
        val periodM = Optional.of((short) 3);

        var incomeStatementReportData = IncomeStatementData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>left(incomeStatementReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        val dispatchedReport = new ReportEntity();
        dispatchedReport.setLedgerDispatchApproved(true);

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.of(dispatchedReport));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("REPORT_ALREADY_APPROVED");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void store_whenReportIsDispatched_shouldNotAllowOverwritingForBalanceSheet() {
        // Arrange
        val organisationId = "org-5";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;
        val periodM = Optional.<Short>empty();

        val balanceSheetReportData = BalanceSheetData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetReportData);

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        val dispatchedReport = new ReportEntity();
        dispatchedReport.setLedgerDispatchApproved(true);

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.of(dispatchedReport));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("REPORT_ALREADY_APPROVED");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void store_whenIncomeStatementIsNotDispatched_shouldAllowOverwriting() {
        // Arrange
        val organisationId = "org-overwrite-1";
        val intervalType = IntervalType.MONTH;
        short year = 2023;
        val ver = 1;
        val periodM = Optional.of((short) 3);

        var incomeStatementReportData = IncomeStatementData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>left(incomeStatementReportData);

        val existingReport = new ReportEntity();
        existingReport.setType(INCOME_STATEMENT);
        existingReport.setLedgerDispatchApproved(false);
        existingReport.setIncomeStatementReportData(Optional.of(incomeStatementReportData));

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.of(existingReport));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isRight()).isTrue();
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void store_whenBalanceSheetIsNotDispatched_shouldAllowOverwriting() {
        // Arrange
        val organisationId = "org-overwrite-2";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;

        val periodM = Optional.<Short>empty();

        val balanceSheetReportData = BalanceSheetData.builder()
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

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(balanceSheetReportData);

        val existingReport = new ReportEntity();
        existingReport.setType(BALANCE_SHEET);
        existingReport.setLedgerDispatchApproved(false);
        existingReport.setBalanceSheetReportData(Optional.of(balanceSheetReportData));

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(any())).thenReturn(Optional.of(existingReport));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isRight()).isTrue();
        verify(reportRepository).save(any(ReportEntity.class));
    }

    @Test
    void store_whenTwoReportsHaveDifferentReportIds_shouldNotAffectEachOther() {
        // Arrange
        val organisationId = "org-different-reports";
        val intervalType = IntervalType.MONTH;
        short year = 2023;
        val ver = 1;
        val periodMarch = Optional.of((short) 3); // March
        val periodApril = Optional.of((short) 4); // April


        var incomeStatementMarchData = IncomeStatementData.builder()
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

        var incomeStatementAprilData = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(new BigDecimal("15000.90"))
                        .buildOfLongTermProvision(new BigDecimal("2000000.10"))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(new BigDecimal("750000.15"))
                        .build())
                .financialIncome(IncomeStatementData.FinancialIncome.builder()
                        .financialRevenues(new BigDecimal("300000.53"))
                        .netIncomeOptionsSale(new BigDecimal("200000.10"))
                        .realisedGainsOnSaleOfCryptocurrencies(new BigDecimal("100000.15"))
                        .stakingRewardsIncome(new BigDecimal("20000.53"))
                        .financialExpenses(new BigDecimal("40000.10"))
                        .build())
                .extraordinaryIncome(IncomeStatementData.ExtraordinaryIncome.builder()
                        .extraordinaryExpenses(new BigDecimal("20000.10"))
                        .build())
                .taxExpenses(IncomeStatementData.TaxExpenses.builder()
                        .directTaxes(new BigDecimal("2000.51"))
                        .build())
                .operatingExpenses(IncomeStatementData.OperatingExpenses.builder()
                        .personnelExpenses(new BigDecimal("750000.15"))
                        .generalAndAdministrativeExpenses(new BigDecimal("300000.53"))
                        .build())
                .build();

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(Report.id(organisationId, INCOME_STATEMENT, intervalType, year, ver, periodMarch)))
                .thenReturn(Optional.empty());
        when(reportRepository.findById(Report.id(organisationId, INCOME_STATEMENT, intervalType, year, ver, periodApril)))
                .thenReturn(Optional.empty());

        // Act
        val resultMarch = reportService.store(organisationId, intervalType, year, ver, periodMarch, Either.left(incomeStatementMarchData));
        val resultApril = reportService.store(organisationId, intervalType, year, ver, periodApril, Either.left(incomeStatementAprilData));

        // Assert
        assertThat(resultMarch.isRight()).isTrue();
        assertThat(resultApril.isRight()).isTrue();

        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportRepository, times(2)).save(captor.capture());

        var savedReports = captor.getAllValues();
        assertThat(savedReports).hasSize(2);
        assertThat(savedReports.get(0).getReportId())
                .isEqualTo(Report.id(organisationId, INCOME_STATEMENT, intervalType, year, ver, periodMarch));

        assertThat(savedReports.get(1).getReportId())
                .isEqualTo(Report.id(organisationId, INCOME_STATEMENT, intervalType, year, ver, periodApril));
    }

    @Test
    void store_whenTwoBalanceSheetReportsHaveDifferentReportIds_shouldNotAffectEachOther() {
        // Arrange
        val organisationId = "org-different-reports";
        val intervalType = IntervalType.MONTH;
        short year = 2023;
        val ver = 1;
        val periodMarch = Optional.of((short) 3); // March
        val periodApril = Optional.of((short) 4); // April

        var balanceSheetMarchData = BalanceSheetData.builder()
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

        var balanceSheetAprilData = BalanceSheetData.builder()
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

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));
        when(reportRepository.findById(Report.id(organisationId, BALANCE_SHEET, intervalType, year, ver, periodMarch)))
                .thenReturn(Optional.empty());
        when(reportRepository.findById(Report.id(organisationId, BALANCE_SHEET, intervalType, year, ver, periodApril)))
                .thenReturn(Optional.empty());

        // Act
        val resultMarch = reportService.store(organisationId, intervalType, year, ver, periodMarch, Either.right(balanceSheetMarchData));
        val resultApril = reportService.store(organisationId, intervalType, year, ver, periodApril, Either.right(balanceSheetAprilData));

        // Assert
        assertThat(resultMarch.isRight()).isTrue();
        assertThat(resultApril.isRight()).isTrue();

        ArgumentCaptor<ReportEntity> captor = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportRepository, times(2)).save(captor.capture());

        var savedReports = captor.getAllValues();
        assertThat(savedReports).hasSize(2);
        assertThat(savedReports.get(0).getReportId())
                .isEqualTo(Report.id(organisationId, BALANCE_SHEET, intervalType, year, ver, periodMarch));

        assertThat(savedReports.get(1).getReportId())
                .isEqualTo(Report.id(organisationId, BALANCE_SHEET, intervalType, year, ver, periodApril));
    }

    @Test
    void store_whenIncomeStatementProfitDoesNotMatchBalanceSheet_shouldReturnProblem() {
        // Arrange
        val organisationId = "org-profit-mismatch";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val ver = 1;
        val periodM = Optional.of((short) 3); // March

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));

        // Existing Balance Sheet with profitForTheYear = 200000.00
        val existingBalanceSheet = new ReportEntity();
        existingBalanceSheet.setType(BALANCE_SHEET);
        existingBalanceSheet.setBalanceSheetReportData(Optional.of(BalanceSheetData.builder()
                .capital(BalanceSheetData.Capital.builder()
                        .profitForTheYear(new BigDecimal("200000.00")) // Existing profit
                        .build())
                .build()));

        // New Income Statement with mismatched profitForTheYear = 150000.00
        val newIncomeStatement = IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(new BigDecimal("10000.90"))
                        .buildOfLongTermProvision(new BigDecimal("1000000.10"))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(new BigDecimal("500000.15"))
                        .build())
                .profitForTheYear(new BigDecimal("150000.00")) // New profit mismatch
                .build();

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>left(newIncomeStatement);

        // Mocking the repository behavior
        when(reportRepository.findById(Report.id(organisationId, BALANCE_SHEET, intervalType, year, ver, periodM)))
                .thenReturn(Optional.of(existingBalanceSheet));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("PROFIT_FOR_THE_YEAR_MISMATCH");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void store_whenBalanceSheetProfitDoesNotMatchIncomeStatement_shouldReturnProblem() {
        // Arrange
        val organisationId = "org-profit-mismatch";
        val intervalType = IntervalType.YEAR;
        short year = 2024;
        val periodM = Optional.of((short) 3); // March
        val ver = 1;

        val organisation = new Organisation();
        organisation.setId(organisationId);
        organisation.setName("Org Name");
        organisation.setCountryCode("US");
        organisation.setCurrencyId("ISO_4217:USD");
        organisation.setTaxIdNumber("12345");

        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));

        // Existing Income Statement with profitForTheYear = 200000.00
        val existingIncomeStatement = new ReportEntity();
        existingIncomeStatement.setType(INCOME_STATEMENT);
        existingIncomeStatement.setIncomeStatementReportData(Optional.of(IncomeStatementData.builder()
                .revenues(IncomeStatementData.Revenues.builder()
                        .otherIncome(new BigDecimal("10000.90"))
                        .buildOfLongTermProvision(new BigDecimal("1000000.10"))
                        .build())
                .costOfGoodsAndServices(IncomeStatementData.CostOfGoodsAndServices.builder()
                        .externalServices(new BigDecimal("500000.15"))
                        .build())
                .profitForTheYear(new BigDecimal("200000.00")) // Existing profit
                .build()));

        // New Balance Sheet with mismatched profitForTheYear = 150000.00
        val newBalanceSheet = BalanceSheetData.builder()
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
                        .profitForTheYear(new BigDecimal("150000.00")) // New profit mismatch
                        .resultsCarriedForward(new BigDecimal("200000.00"))
                        .build())
                .build();

        val reportDataE = Either.<IncomeStatementData, BalanceSheetData>right(newBalanceSheet);

        // Mocking the repository behavior
        when(reportRepository.findById(Report.id(organisationId, INCOME_STATEMENT, intervalType, year, ver, periodM)))
                .thenReturn(Optional.of(existingIncomeStatement));

        // Act
        val resultE = reportService.store(organisationId, intervalType, year, ver, periodM, reportDataE);

        // Assert
        assertThat(resultE.isLeft()).isTrue();
        assertThat(resultE.getLeft().getTitle()).isEqualTo("PROFIT_FOR_THE_YEAR_MISMATCH");
        verify(reportRepository, never()).save(any(ReportEntity.class));
    }

    @Test
    void storeReport_successfull() {
        IntervalType intervalType = IntervalType.MONTH;
        short year = 2025;
        short period = 3;
        String organisationId = "org-123";
        Organisation organisation = Mockito.mock(Organisation.class);
        ReportEntity reportEntity = Mockito.mock(ReportEntity.class);

        when(organisation.getCountryCode()).thenReturn("CountryCode");
        when(organisation.getCurrencyId()).thenReturn("CurrencyId");
        when(organisation.getTaxIdNumber()).thenReturn("TaxIdNumber");
        when(organisation.getName()).thenReturn("Name");
        when(reportEntity.getLedgerDispatchApproved()).thenReturn(false); // When LedgerDispatchApproved is true a new report is created
        when(reportEntity.getReportId()).thenReturn("reportId");
        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.of(organisation));

        when(reportRepository.findLatestByIdControl(anyString(), anyString())).thenReturn(Optional.of(reportEntity));
        when(reportRepository.save(any(ReportEntity.class))).thenReturn(reportEntity);

        Either<Problem, ReportEntity> result = reportService.storeReport(BALANCE_SHEET, CreateReportView.builder()
                .organisationId(organisationId)
                .balanceSheetData(Optional.of(BalanceSheetData.builder().build())).build(), intervalType, year, period);

        assertTrue(result.isRight());

        verify(organisationPublicApi).findByOrganisationId(organisationId);
        verify(reportRepository).findLatestByIdControl("org-123", "acf103248617fb66012ed41c275c48f71f29a1298074242728292ddf800fced9");
        verify(reportRepository, times(1)).save(any(ReportEntity.class));
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(reportRepository);
    }

    @Test
    void storeBalanceSheetTestWrongType_ShouldReturnProblem() {
        IntervalType intervalType = IntervalType.MONTH;
        short year = 2025;
        short period = 3;
        String organisationId = "org-123";

        Either<Problem, ReportEntity> result = reportService.storeReport(BALANCE_SHEET, CreateReportView.builder()
                .organisationId(organisationId)
                .balanceSheetData(Optional.empty())
                .incomeStatementData(Optional.of(IncomeStatementData.builder().build())).build(), intervalType, year, period);

        assertTrue(result.isLeft());
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_REPORT_TYPE");
        verifyNoInteractions(organisationPublicApi);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void storeIncomeStatementTestWrongType_ShouldReturnProblem() {
        IntervalType intervalType = IntervalType.MONTH;
        short year = 2025;
        short period = 3;
        String organisationId = "org-123";

        Either<Problem, ReportEntity> result = reportService.storeReport(INCOME_STATEMENT, CreateReportView.builder()
                .organisationId(organisationId)
                .incomeStatementData(Optional.empty())
                .balanceSheetData(Optional.of(BalanceSheetData.builder().build())).build(), intervalType, year, period);

        assertTrue(result.isLeft());
        assertThat(result.getLeft().getTitle()).isEqualTo("INVALID_REPORT_TYPE");
        verifyNoInteractions(organisationPublicApi);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void storeReportOrganisationNotFound_ShouldReturnProblem() {
        IntervalType intervalType = IntervalType.MONTH;
        short year = 2025;
        short period = 3;
        String organisationId = "org-123";
        when(organisationPublicApi.findByOrganisationId(organisationId)).thenReturn(Optional.empty());

        Either<Problem, ReportEntity> result = reportService.storeReport(BALANCE_SHEET, CreateReportView.builder()
                .organisationId(organisationId)
                .incomeStatementData(Optional.empty())
                .balanceSheetData(Optional.of(BalanceSheetData.builder().build())).build(), intervalType, year, period);

        assertTrue(result.isLeft());
        assertThat(result.getLeft().getTitle()).isEqualTo("ORGANISATION_NOT_FOUND");
        verify(organisationPublicApi).findByOrganisationId(organisationId);
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void reportGenerate_OrgNotFound() {
        String organisationId = "org-123";
        ReportGenerateRequest request = new ReportGenerateRequest(BALANCE_SHEET, IntervalType.YEAR, (short) 2025, (short) 1);
        request.setOrganisationId(organisationId);
        when(reportTypeRepository.findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name())).thenReturn(Optional.empty());

        Either<Problem, ReportEntity> result = reportService.reportGenerate(request);

        assertTrue(result.isLeft());
        assertThat(result.getLeft().getTitle()).isEqualTo("REPORT_SETUP_NOT_FOUND");
        verify(reportTypeRepository).findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name());
    }

    @Test
    void reportGenerate_generateBalanceSheet() {
        String organisationId = "org-123";
        ReportTypeEntity reportTypeEntity = mock(ReportTypeEntity.class);
        ReportGenerateRequest request = new ReportGenerateRequest(BALANCE_SHEET, IntervalType.YEAR, (short) 2025, (short) 1);
        request.setOrganisationId(organisationId);
        ReportTypeFieldEntity reportTypeFieldEntityCapital = mock(ReportTypeFieldEntity.class);
        ReportTypeFieldEntity reportTypeFieldEntityProfit = mock(ReportTypeFieldEntity.class);
        OrganisationChartOfAccountSubType organisationChartOfAccountSubType = mock(OrganisationChartOfAccountSubType.class);
        OrganisationChartOfAccount organisationChartOfAccount = mock(OrganisationChartOfAccount.class);
        OrganisationChartOfAccount.Id organisationChartOfAccountId = mock(OrganisationChartOfAccount.Id.class);
        TransactionItemEntity transactionItemEntity = mock(TransactionItemEntity.class);
        LocalDate startDate = LocalDate.of(2025, 1, 1);
        LocalDate endDate = LocalDate.of(2025, 12, 31);

        when(reportTypeRepository.findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name())).thenReturn(Optional.of(reportTypeEntity));
        when(reportTypeEntity.getFields()).thenReturn(List.of(reportTypeFieldEntityCapital));
        when(reportTypeFieldEntityCapital.getParent()).thenReturn(null);
        when(reportTypeFieldEntityCapital.getName()).thenReturn("CAPITAL");
        when(reportTypeFieldEntityProfit.getName()).thenReturn("PROFIT_FOR_THE_YEAR");
        when(reportTypeFieldEntityCapital.getChildFields()).thenReturn(List.of(reportTypeFieldEntityProfit));
        when(reportTypeFieldEntityProfit.getMappingTypes()).thenReturn(List.of(organisationChartOfAccountSubType));
        when(organisationChartOfAccountSubType.getId()).thenReturn(1L);
        when(chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(List.of(1L))).thenReturn(Set.of(organisationChartOfAccount));
        when(reportTypeFieldEntityProfit.isAccumulated()).thenReturn(false);
        when(reportTypeFieldEntityProfit.isAccumulatedYearly()).thenReturn(false);
        when(organisationChartOfAccount.getId()).thenReturn(organisationChartOfAccountId);
        when(organisationChartOfAccountId.getCustomerCode()).thenReturn("CustomerCode");
        when(transactionItemRepository.findTransactionItemsByAccountCodeAndDateRange(List.of("CustomerCode"), startDate, endDate)).thenReturn(List.of(transactionItemEntity));
        when(transactionItemEntity.getOperationType()).thenReturn(OperationType.DEBIT);
        when(transactionItemEntity.getAmountLcy()).thenReturn(BigDecimal.TEN);

        Either<Problem, ReportEntity> result = reportService.reportGenerate(request);

        assertTrue(result.isRight());
        verify(reportTypeRepository).findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name());
        ReportEntity reportEntity = result.get();
        Optional<BalanceSheetData> balanceSheetReportData = reportEntity.getBalanceSheetReportData();
        assertTrue(balanceSheetReportData.isPresent());
        BalanceSheetData balanceSheetData = balanceSheetReportData.get();
        BigDecimal profitForTheYear = balanceSheetData.getCapital().get().getProfitForTheYear().get();
        assertThat(profitForTheYear).isEqualTo(BigDecimal.ZERO);

    }

    @Test
    void reportGenerate_generateNoChilds() {
        String organisationId = "org-123";
        ReportTypeEntity reportTypeEntity = mock(ReportTypeEntity.class);
        ReportGenerateRequest request = new ReportGenerateRequest(BALANCE_SHEET, IntervalType.YEAR, (short) 2025, (short) 1);
        request.setOrganisationId(organisationId);
        ReportTypeFieldEntity field = mock(ReportTypeFieldEntity.class);

        when(reportTypeRepository.findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name())).thenReturn(Optional.of(reportTypeEntity));
        when(reportTypeEntity.getFields()).thenReturn(List.of(field));
        when(reportTypeRepository.findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name())).thenReturn(Optional.of(reportTypeEntity));
        when(field.getParent()).thenReturn(null);


        Either<Problem, ReportEntity> reportEntityE = reportService.reportGenerate(request);


        assertTrue(reportEntityE.isRight());
        ReportEntity reportEntity = reportEntityE.get();
        assertEquals(Optional.empty(), reportEntity.getIncomeStatementReportData());
        assertNotNull(reportEntity.getBalanceSheetReportData());
        reportEntity.getBalanceSheetReportData().ifPresent(balanceSheetData -> {
            assertEquals(Optional.empty(), balanceSheetData.getAssets());
            assertEquals(Optional.empty(), balanceSheetData.getLiabilities());
            assertEquals(Optional.empty(), balanceSheetData.getCapital());
        });
    }

    @Test
    void findReportInRange_emptySet() {

        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Collections.emptySet());

        Set<ReportEntity> reportsInDateRange = reportService.findReportsInDateRange("org-123", INCOME_STATEMENT, Optional.of(LocalDate.now()), Optional.of(LocalDate.now()));

        assertTrue(reportsInDateRange.isEmpty());
    }

    @Test
    void findReportInRange_filterRemove() {
        ReportEntity mockEntity = mock(ReportEntity.class);
        when(mockEntity.getReportId()).thenReturn("reportId");
        when(mockEntity.getType()).thenReturn(INCOME_STATEMENT);
        when(mockEntity.getYear()).thenReturn((short) 2025);
        when(mockEntity.getIntervalType()).thenReturn(IntervalType.YEAR);
        when(mockEntity.getPeriod()).thenReturn(Optional.of((short) 1));
        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Set.of(mockEntity));

        Set<ReportEntity> reportsInDateRange = reportService.findReportsInDateRange("org-123", INCOME_STATEMENT,
                Optional.of(LocalDate.of(2025, Month.JANUARY, 1)),
                Optional.of(LocalDate.of(2025, Month.JANUARY, 31)));

        assertTrue(reportsInDateRange.isEmpty());
    }

    @Test
    void findReportInRange_filterMonthAdd() {
        ReportEntity mockEntity = mock(ReportEntity.class);
        when(mockEntity.getReportId()).thenReturn("reportId");
        when(mockEntity.getType()).thenReturn(INCOME_STATEMENT);
        when(mockEntity.getYear()).thenReturn((short) 2025);
        when(mockEntity.getIntervalType()).thenReturn(IntervalType.MONTH);
        when(mockEntity.getPeriod()).thenReturn(Optional.of((short) 1));
        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Set.of(mockEntity));

        Set<ReportEntity> reportsInDateRange = reportService.findReportsInDateRange("org-123", INCOME_STATEMENT,
                Optional.of(LocalDate.of(2025, Month.JANUARY, 1)),
                Optional.of(LocalDate.of(2025, Month.JANUARY, 31)));

        Assertions.assertEquals(1, reportsInDateRange.size());
    }

    @Test
    void findReportInRange_filterMonthAddAndFilter() {
        ReportEntity mockJan1 = mock(ReportEntity.class);
        when(mockJan1.getReportId()).thenReturn("reportId");
        when(mockJan1.getType()).thenReturn(INCOME_STATEMENT);
        when(mockJan1.getYear()).thenReturn((short) 2025);
        when(mockJan1.getIntervalType()).thenReturn(IntervalType.MONTH);
        when(mockJan1.getPeriod()).thenReturn(Optional.of((short) 1));
        when(mockJan1.getVer()).thenReturn(1L);

        ReportEntity mockJan2 = mock(ReportEntity.class);
        when(mockJan2.getReportId()).thenReturn("reportId");
        when(mockJan2.getType()).thenReturn(INCOME_STATEMENT);
        when(mockJan2.getYear()).thenReturn((short) 2025);
        when(mockJan2.getIntervalType()).thenReturn(IntervalType.MONTH);
        when(mockJan2.getPeriod()).thenReturn(Optional.of((short) 1));
        when(mockJan2.getVer()).thenReturn(2L);

        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Set.of(mockJan1, mockJan2));

        Set<ReportEntity> reportsInDateRange = reportService.findReportsInDateRange("org-123", INCOME_STATEMENT,
                Optional.of(LocalDate.of(2025, Month.JANUARY, 1)),
                Optional.of(LocalDate.of(2025, Month.DECEMBER, 31)));

        Assertions.assertEquals(1, reportsInDateRange.size());
        Assertions.assertTrue(reportsInDateRange.contains(mockJan2));
    }

    @Test
    void findReportInRange_filterQuarterAndYear() {
        ReportEntity mockQ1 = mock(ReportEntity.class);
        when(mockQ1.getReportId()).thenReturn("reportId");
        when(mockQ1.getType()).thenReturn(INCOME_STATEMENT);
        when(mockQ1.getYear()).thenReturn((short) 2025);
        when(mockQ1.getIntervalType()).thenReturn(IntervalType.QUARTER);
        when(mockQ1.getPeriod()).thenReturn(Optional.of((short) 1));
        when(mockQ1.getVer()).thenReturn(1L);

        ReportEntity mockYear = mock(ReportEntity.class);
        when(mockYear.getReportId()).thenReturn("reportId");
        when(mockYear.getType()).thenReturn(INCOME_STATEMENT);
        when(mockYear.getYear()).thenReturn((short) 2025);
        when(mockYear.getIntervalType()).thenReturn(IntervalType.YEAR);
        when(mockYear.getPeriod()).thenReturn(Optional.of((short) 1));
        when(mockYear.getVer()).thenReturn(2L);

        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Set.of(mockQ1, mockYear));

        Set<ReportEntity> reportsInDateRange = reportService.findReportsInDateRange("org-123", INCOME_STATEMENT,
                Optional.of(LocalDate.of(2025, Month.JANUARY, 1)),
                Optional.of(LocalDate.of(2025, Month.DECEMBER, 31)));

        Assertions.assertEquals(1, reportsInDateRange.size());
        Assertions.assertTrue(reportsInDateRange.contains(mockYear));
    }

    @Test
    void reportGenerate_generateFromOtherReport() {
        String organisationId = "org-123";
        ReportTypeEntity reportTypeEntity = mock(ReportTypeEntity.class);
        ReportGenerateRequest request = new ReportGenerateRequest(BALANCE_SHEET, IntervalType.YEAR, (short) 2025, (short) 1);
        request.setOrganisationId(organisationId);
        ReportTypeFieldEntity field = mock(ReportTypeFieldEntity.class);
        ReportTypeFieldEntity parentField = mock(ReportTypeFieldEntity.class);
        ReportTypeFieldEntity mappedType = mock(ReportTypeFieldEntity.class);
        ReportTypeEntity mockReportTypeEntity = mock(ReportTypeEntity.class);

        ReportEntity mockQ1 = mock(ReportEntity.class);
        IncomeStatementData incomeStatementReportData = new IncomeStatementData(null, null, null, null, null, null, BigDecimal.TEN);

        when(mockQ1.getReportId()).thenReturn("reportId");
        when(mockQ1.getType()).thenReturn(INCOME_STATEMENT);
        when(mockQ1.getYear()).thenReturn((short) 2025);
        when(mockQ1.getIntervalType()).thenReturn(IntervalType.QUARTER);
        when(mockQ1.getPeriod()).thenReturn(Optional.of((short) 1));
        when(mockQ1.getVer()).thenReturn(1L);
        when(mockQ1.getIncomeStatementReportData()).thenReturn(Optional.of(incomeStatementReportData));
        when(mappedType.getReport()).thenReturn(mockReportTypeEntity);
        when(mappedType.getName()).thenReturn("PROFIT_FOR_THE_YEAR");
        when(parentField.getName()).thenReturn("CAPITAL");
        when(parentField.getParent()).thenReturn(null);
        when(parentField.getChildFields()).thenReturn(List.of(field));
        when(field.getParent()).thenReturn(parentField);
        when(field.getName()).thenReturn("PROFIT_FOR_THE_YEAR");
        when(mockReportTypeEntity.getName()).thenReturn("INCOME_STATEMENT");
        when(mockReportTypeEntity.getOrganisationId()).thenReturn("org-123");


        when(reportTypeRepository.findByOrganisationAndReportName(organisationId, BALANCE_SHEET.name())).thenReturn(Optional.of(reportTypeEntity));
        when(reportTypeEntity.getFields()).thenReturn(List.of(field, parentField));
        when(field.getMappingReportTypes()).thenReturn(List.of(mappedType));
        when(mappedType.getName()).thenReturn("PROFIT_FOR_THE_YEAR");

        when(reportRepository.findByTypeAndWithinYearRange("org-123", INCOME_STATEMENT, (short) 2025, (short) 2025))
                .thenReturn(Set.of(mockQ1));


        Either<Problem, ReportEntity> reportEntityE = reportService.reportGenerate(request);


        Assertions.assertTrue(reportEntityE.isRight());
        ReportEntity reportEntity = reportEntityE.get();
        Assertions.assertTrue(reportEntity.getBalanceSheetReportData().isPresent());
        BalanceSheetData balanceSheetData = reportEntity.getBalanceSheetReportData().get();
        Assertions.assertTrue(balanceSheetData.getCapital().isPresent());
        Assertions.assertEquals(Optional.of(BigDecimal.TEN), balanceSheetData.getCapital().get().getProfitForTheYear());

    }

}
