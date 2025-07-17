package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

public class Constants {

    private Constants() {
        // Utility class, no instantiation
    }

    public static final String ORGANISATION_WITH_ID_S_DOES_NOT_EXIST = "Organisation with ID %s does not exist.";
    public static final String ORGANISATION_NOT_FOUND = "ORGANISATION_NOT_FOUND";
    public static final String ORGANISATION_ID = "organisationId";
    public static final String REPORT_NOT_FOUND = "REPORT_NOT_FOUND";
    public static final String REPORT_WITH_ID_S_DOES_NOT_EXIST = "Report with ID %s does not exist.";
    public static final String REPORT_ID = "reportId";
    public static final String REPORT_NOT_READY_FOR_PUBLISHING = "REPORT_NOT_READY_FOR_PUBLISHING";
    public static final String REPORT_WITH_ID_S_IS_NOT_READY_FOR_PUBLISHING = "Report with ID %s is not ready for publishing.";
    public static final String INVALID_REPORT = "INVALID_REPORT";
    public static final String REPORT_IS_NOT_VALID_SINCE_IT_DIDN_T_PASS_THROUGH_BUSINESS_CHECKS = "Report is not valid since it didn't pass through business checks.";
    public static final String REPORT_TYPE = "reportType";
    public static final String INVALID_REPORT_TYPE = "INVALID_REPORT_TYPE";
    public static final String REPORT_TYPE_IS_NOT_VALID_EXPECTED_BALANCE_SHEET_BUT_GOT_S = "Report type is not valid. Expected BALANCE_SHEET but got %s.";
    public static final String REPORT_ALREADY_APPROVED = "REPORT_ALREADY_APPROVED";
    public static final String REPORT_WITH_ID_S_HAS_ALREADY_BEEN_APPROVED_FOR_LEDGER_DISPATCH = "Report with ID %s has already been approved for ledger dispatch.";
    public static final String PROFIT_FOR_THE_YEAR_MISMATCH = "PROFIT_FOR_THE_YEAR_MISMATCH";
    public static final String PROFIT_FOR_THE_YEAR_DOES_NOT_MATCH_THE_RELATED_REPORT = "Profit for the year does not match the related report.";
    public static final String INVALID_REPORT_DATA = "INVALID_REPORT_DATA";
    public static final String INCOME_STATEMENT_REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED = "Income Statement report data is not valid. Business Checks failed.";
    public static final String BALANCE_SHEET_REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED = "Balance Sheet report data is not valid. Business Checks failed.";
    public static final String EMPTY_REPORT_DATA = "EMPTY_REPORT_DATA";
    public static final String REPORT_IS_EMPTY = "Report is empty.";
    public static final String REPORT_DATA_IS_NOT_VALID_BUSINESS_CHECKS_FAILED = "Report data is not valid. Business Checks failed.";
    public static final String PROFIT_FOR_THE_YEAR_DOES_NOT_MATCH_THE_RELATED_REPORT_S_S = "Profit for the year does not match the related report. %s != %s";
    public static final String REPORT_SETUP_NOT_FOUND = "REPORT_SETUP_NOT_FOUND";
    public static final String REPORT_SETUP_FOR_S_NOT_FOUND = "Report setup for %s not found.";
    public static final String REPORT_TYPE_IS_NOT_VALID_EXPECTED_BALANCE_SHEET_OR_INCOME_STATEMENT_BUT_GOT_S = "Report type is not valid. Expected BALANCE_SHEET or INCOME_STATEMENT but got %s.";
    public static final String REPORT_ALREADY_DISPATCHED = "REPORT_ALREADY_DISPATCHED";
    public static final String REPORT_WITH_ID_S_HAS_ALREADY_BEEN_DISPATCHED = "Report with ID %s has already been dispatched.";
}
