package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report;

import static org.cardanofoundation.lob.app.support.crypto.SHA3.digestAsHex;

import java.time.LocalDate;
import java.util.Optional;

import lombok.*;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Organisation;

@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class Report {

    private String reportId;

    private String idReport;

    private Organisation organisation;

    private ReportType type;

    private IntervalType intervalType;

    private short year;

    private long ver;

    @Builder.Default
    private Optional<Short> period = Optional.empty();

    @Builder.Default
    private ReportMode mode = ReportMode.USER;

    private LocalDate date;

    @Builder.Default
    private Optional<BalanceSheetData> balanceSheetData = Optional.empty();

    @Builder.Default
    private Optional<IncomeStatementData> incomeStatementData = Optional.empty();

    @Builder.Default
    private boolean reportApproved = false;

    @Builder.Default
    private boolean ledgerDispatchApproved = false;

    @Builder.Default
    private LedgerDispatchStatus ledgerDispatchStatus = LedgerDispatchStatus.NOT_DISPATCHED;

    public static String id(String organisationId,
                            ReportType reportType,
                            IntervalType intervalType,
                            short year,
                            long ver,
                            Optional<Short> period) {
        return period.map(p -> digestAsHex("%s::%s::%s::%s::%s::%s".formatted(organisationId, reportType, intervalType, year, ver, p)))
                .orElseGet(() -> digestAsHex("%s::%s::%s::%s::%s".formatted(organisationId, reportType, intervalType, year, ver)));
    }

    public static String idControl(String organisationId,
                            ReportType reportType,
                            IntervalType intervalType,
                            short year,
                            Optional<Short> period) {
        return period.map(p -> digestAsHex("%s::%s::%s::%s::%s".formatted(organisationId, reportType, intervalType, year, p)))
                .orElseGet(() -> digestAsHex("%s::%s::%s::%s".formatted(organisationId, reportType, intervalType, year)));
    }

    public void setBalanceSheetData(Optional<BalanceSheetData> balanceSheetData) {
        if (balanceSheetData != null && balanceSheetData.isPresent() && type != ReportType.BALANCE_SHEET) {
            throw new IllegalArgumentException("Balance sheet data can only be set for balance sheet report type!");
        }

        this.balanceSheetData = balanceSheetData;
    }

    public void setIncomeStatementData(Optional<IncomeStatementData> incomeStatementData) {
        if (incomeStatementData != null && incomeStatementData.isPresent() && type != ReportType.INCOME_STATEMENT) {
            throw new IllegalArgumentException("Income statement data can only be set for income statement report type!");
        }

        this.incomeStatementData = incomeStatementData;
    }

}
