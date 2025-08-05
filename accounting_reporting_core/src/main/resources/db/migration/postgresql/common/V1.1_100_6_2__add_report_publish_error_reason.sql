CREATE TYPE accounting_core_report_publish_error AS ENUM (
    'INVALID_REPORT_DATA',
    'PROFIT_FOR_THE_YEAR_MISMATCH',
    'REPORT_DATA_MISMATCH'
    );

ALTER TABLE accounting_core_report ADD publish_error accounting_core_report_publish_error;
ALTER TABLE accounting_core_report_aud ADD publish_error accounting_core_report_publish_error;
