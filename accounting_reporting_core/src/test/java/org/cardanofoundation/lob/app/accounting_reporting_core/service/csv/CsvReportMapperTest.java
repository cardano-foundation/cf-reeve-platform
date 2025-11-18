package org.cardanofoundation.lob.app.accounting_reporting_core.service.csv;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportCsvLine;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.BalanceSheetData;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.report.IncomeStatementData;
import org.cardanofoundation.lob.app.accounting_reporting_core.resource.views.CreateReportView;

@ExtendWith(MockitoExtension.class)
class CsvReportMapperTest {

    @InjectMocks
    private CsvReportMapper csvReportMapper;

    @Test
    void mapCsvLinesToReportEntity_empty() {
        List<ReportCsvLine> csvLines = new ArrayList<>();
        Either<Problem, CreateReportView> response = csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isEmpty());
        Problem left = response.getLeft();
        assertTrue(left.getStatus().getStatusCode() == 400);
        assertTrue(left.getTitle().equals("CSV_PARSING_ERROR"));
    }

    @Test
    void mapCsvLinesToReportEntity_invalidReportType() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("INVALID_TYPE");
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response = csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isLeft());
        Problem left = response.getLeft();
        assertTrue(left.getStatus().getStatusCode() == 400);
        assertTrue(left.getTitle().equals("INVALID_REPORT_TYPE"));
    }

    @Test
    void mapCsvLinesToReportEntity_invalidIntervalType() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("BALANCE_SHEET");
        when(line1.getIntervalType()).thenReturn("INVALID_INTERVAL");
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isLeft());
        Problem left = response.getLeft();
        assertTrue(left.getStatus().getStatusCode() == 400);
        assertTrue(left.getTitle().equals("INVALID_INTERVAL_TYPE"));
    }

    @Test
    void mapCsvLinesToReportEntity_balanceSheetWrongField() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("BALANCE_SHEET");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("WRONG_FIELD");
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isLeft());
        Problem left = response.getLeft();
        assertTrue(left.getStatus().getStatusCode() == 400);
        assertTrue(left.getTitle().equals("CSV_PARSING_ERROR"));
    }

    @Test
    void mapCsvLinesToReportEntity_balanceSheet() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("BALANCE_SHEET");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("tangibleAssets");
        when(line1.getAmount()).thenReturn(100.0);
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isRight());
        CreateReportView createReportView = response.get();
        Optional<BalanceSheetData> balanceSheetData = createReportView.getBalanceSheetData();
        assertTrue(balanceSheetData.isPresent());
        Optional<BigDecimal> tangibleAssets = balanceSheetData.get().getAssets().get().getNonCurrentAssets().get().getTangibleAssets();
        assertTrue(tangibleAssets.isPresent());
        assertTrue(tangibleAssets.get().compareTo(BigDecimal.valueOf(100.0)) == 0);
    }

    @Test
    void mapCsvLinesToReportEntity_incomeStatement() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("INCOME_STATEMENT");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("otherIncome");
        when(line1.getAmount()).thenReturn(100.0);
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isRight());
        CreateReportView createReportView = response.get();
        Optional<IncomeStatementData> incomeStatementData = createReportView.getIncomeStatementData();
        assertTrue(incomeStatementData.isPresent());
        Optional<BigDecimal> otherIncome = incomeStatementData.get().getRevenues().get().getOtherIncome();
        assertTrue(otherIncome.isPresent());
        assertTrue(otherIncome.get().compareTo(BigDecimal.valueOf(100.0)) == 0);
    }

    @Test
    void mapCsvLinesToReportEntity_incomeStatementFullPath() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("INCOME_STATEMENT");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("revenues.otherIncome");
        when(line1.getAmount()).thenReturn(100.0);
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isRight());
        CreateReportView createReportView = response.get();
        Optional<IncomeStatementData> incomeStatementData =
                createReportView.getIncomeStatementData();
        assertTrue(incomeStatementData.isPresent());
        Optional<BigDecimal> otherIncome =
                incomeStatementData.get().getRevenues().get().getOtherIncome();
        assertTrue(otherIncome.isPresent());
        assertTrue(otherIncome.get().compareTo(BigDecimal.valueOf(100.0)) == 0);
    }

    @Test
    void mapCsvLinesToReportEntity_incomeStatementFullPathUnderscore() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("INCOME_STATEMENT");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("revenues_otherIncome");
        when(line1.getAmount()).thenReturn(100.0);
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isRight());
        CreateReportView createReportView = response.get();
        Optional<IncomeStatementData> incomeStatementData =
                createReportView.getIncomeStatementData();
        assertTrue(incomeStatementData.isPresent());
        Optional<BigDecimal> otherIncome =
                incomeStatementData.get().getRevenues().get().getOtherIncome();
        assertTrue(otherIncome.isPresent());
        assertTrue(otherIncome.get().compareTo(BigDecimal.valueOf(100.0)) == 0);
    }

    @Test
    void mapCsvLinesToReportEntity_incomeStatementFullPathSpace() {
        ReportCsvLine line1 = mock(ReportCsvLine.class);
        when(line1.getReport()).thenReturn("INCOME_STATEMENT");
        when(line1.getIntervalType()).thenReturn("QUARTER");
        when(line1.getField()).thenReturn("revenues otherIncome");
        when(line1.getAmount()).thenReturn(100.0);
        List<ReportCsvLine> csvLines = List.of(line1);
        Either<Problem, CreateReportView> response =
                csvReportMapper.mapCsvLinesToReportEntity(csvLines, "org123");
        assertTrue(response.isRight());
        CreateReportView createReportView = response.get();
        Optional<IncomeStatementData> incomeStatementData =
                createReportView.getIncomeStatementData();
        assertTrue(incomeStatementData.isPresent());
        Optional<BigDecimal> otherIncome =
                incomeStatementData.get().getRevenues().get().getOtherIncome();
        assertTrue(otherIncome.isPresent());
        assertTrue(otherIncome.get().compareTo(BigDecimal.valueOf(100.0)) == 0);
    }

}
