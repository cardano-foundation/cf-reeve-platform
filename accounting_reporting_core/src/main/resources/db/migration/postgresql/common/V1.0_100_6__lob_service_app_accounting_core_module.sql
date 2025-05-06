CREATE TYPE accounting_core_ledger_dispatch_status_type AS ENUM (
    'NOT_DISPATCHED',       -- not dispatched to blockchain(s) yet
    'MARK_DISPATCH',        -- acking that we stored in the database of blockchain publisher (marked for dispatch)
    'DISPATCHED',           -- dispatched to blockchain(s) - tx hash
    'COMPLETED',            -- tx hash is ready and provided and in addition we have decent finality score to consider it completed
    'FINALIZED'             -- finalised on blockchain(s) - tx hash (12 hours)
);

CREATE TYPE accounting_core_transaction_status_type AS ENUM (
    'OK',
    'NOK'
);

CREATE TYPE accounting_core_reconcilation_accounting_core_source_type AS ENUM (
    'OK',
    'NOK'
);

CREATE TYPE accounting_core_tx_validation_status_type AS ENUM (
    'VALIDATED',
    'FAILED'
);

CREATE TYPE accounting_core_counter_party_type AS ENUM (
    'EMPLOYEE',
    'VENDOR',
    'DONOR',
    'CLIENT'
);

CREATE TYPE accounting_core_transaction_type AS ENUM (
    'CardCharge',
    'VendorBill',
    'CardRefund',
    'Journal',
    'FxRevaluation',
    'Transfer',
    'CustomerPayment',
    'ExpenseReport',
    'VendorPayment',
    'BillCredit',
    'CustomerInvoice',
    'Unknown'
);

CREATE TYPE accounting_core_reconcilation_rejection_code_type AS ENUM (
    'SOURCE_RECONCILATION_FAIL',  -- transaction version will have to be ignored because it cannot be extracted again
    'SINK_RECONCILATION_FAIL',    -- blockchain is missing a transaction or it is not finalized yet
    'TX_NOT_IN_ERP',              -- transaction is not in ERP, typically this is a technical error
    'TX_NOT_IN_LOB'               -- transaction is not in LOB, typically it has not been imported yet at all
);

CREATE TYPE accounting_core_reconcilation_status_type AS ENUM (
    'CREATED',         -- reconcilation is created but not started yet
    'STARTED',         -- reconcilation is started and first chunk event is sent
    'FAILED',          -- reconcilation is failed
    'COMPLETED'        -- reconcilation is completed, we see this on the last chunk event sent
);

CREATE TYPE accounting_core_transaction_violation_code_type AS ENUM (
    'DOCUMENT_MUST_BE_PRESENT',
    'TX_CANNOT_BE_ALTERED',
    'ACCOUNT_CODE_CREDIT_IS_EMPTY',
    'ACCOUNT_CODE_DEBIT_IS_EMPTY',
    'TX_VALIDATION_ERROR',
    'LCY_BALANCE_MUST_BE_ZERO',
    'FCY_BALANCE_MUST_BE_ZERO',
    'AMOUNT_LCY_IS_ZERO',
    'AMOUNT_FCY_IS_ZERO',
    'ALL_TX_ITEMS_ERASED',
    'VAT_DATA_NOT_FOUND',
    'CORE_CURRENCY_NOT_FOUND',
    'CURRENCY_DATA_NOT_FOUND',
    'COST_CENTER_DATA_NOT_FOUND',
    'PROJECT_DATA_NOT_FOUND',
    'CHART_OF_ACCOUNT_NOT_FOUND',
    'EVENT_DATA_NOT_FOUND',
    'ORGANISATION_DATA_NOT_FOUND',
    'JOURNAL_DUMMY_ACCOUNT_MISSING',
    'TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE',
    'TRANSACTION_TYPE_UNKNOWN'
);

CREATE TYPE accounting_core_source_type AS ENUM (
    'ERP',
    'LOB'
);

CREATE TYPE accounting_core_transaction_batch_status_type AS ENUM (
    'CREATED',        -- job created
    'PROCESSING',     -- job is being processed
    'FINISHED',       -- all transactions processed and valid transactions are dispatched to the blockchain(s)
    'COMPLETE',       -- all VALID transactions that passed business validation are settled to the blockchain(s)
    'FINALIZED',      -- all transactions are settled and the transactions are finalized on the blockchain(s)
    'FAILED'          -- job failed due to e.g. fatal error in the adapter layer
);

CREATE TYPE accounting_core_severity_type AS ENUM (
    'WARN',
    'ERROR'
);

CREATE TYPE accounting_core_tx_item_validation_status_type AS ENUM (
    'OK',
    'ERASED_SELF_PAYMENT',
    'ERASED_SUM_APPLIED',
    'ERASED_ZERO_BALANCE'
);

CREATE TYPE accounting_core_tx_item_operation_type AS ENUM (
    'DEBIT',
    'CREDIT'
);

CREATE TYPE accounting_core_report_type AS ENUM (
    'BALANCE_SHEET',
    'INCOME_STATEMENT'
);

CREATE TYPE accounting_core_report_internal_type AS ENUM (
    'YEAR',
    'QUARTER',
    'MONTH'
);

CREATE TYPE accounting_core_report_mode_type AS ENUM (
    'USER',
    'SYSTEM'
);

CREATE TYPE accounting_core_rejection_reason_type AS ENUM (
    'INCORRECT_AMOUNT',
    'INCORRECT_COST_CENTER',
    'INCORRECT_PROJECT',
    'INCORRECT_CURRENCY',
    'INCORRECT_VAT_CODE',
    'REVIEW_PARENT_COST_CENTER',
    'REVIEW_PARENT_PROJECT_CODE'
);

CREATE TYPE accounting_core_metric_type AS ENUM (
    'BALANCE_SHEET',
    'INCOME_STATEMENT'
);

CREATE TYPE account_core_submetric_type AS ENUM (
    'ASSET_CATEGORIES',
    'BALANCE_SHEET_OVERVIEW',
    'TOTAL_EXPENSES',
    'INCOME_STREAMS',
    'TOTAL_ASSETS',
    'TOTAL_LIABILITIES',
    'PROFIT_OF_THE_YEAR'

);

CREATE TABLE IF NOT EXISTS accounting_core_dashboard (
    id BIGSERIAL PRIMARY KEY,
    organisation_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT
);

-- ISO_4217:CHF or ISO_24165:BSV:2L8HS2MNP, ISO_24165:ADA:HWGL1C2CK, etc
CREATE DOMAIN accounting_core_currency_id_type AS VARCHAR(25)
    CHECK (VALUE ~ '^(ISO_4217:[A-Z]{3})|(ISO_24165:[A-Z]{3}:[A-Z0-9]+)$');

-- ISO 3166-1 alpha-2 country code, e.g. CH, DE, US, etc
CREATE DOMAIN accounting_core_country_code_type AS CHAR(2)
    CHECK (VALUE ~ '^[A-Z]{2}$');

-- YYYY-MM, e.g. 2021-01
CREATE DOMAIN accounting_core_accounting_period_type AS CHAR(7)
    CHECK (VALUE ~ '^[0-9]{4}-[0-9]{2}$');

CREATE TABLE IF NOT EXISTS accounting_core_transaction_batch (
   transaction_batch_id CHAR(64) NOT NULL,

   status accounting_core_transaction_batch_status_type NOT NULL,

   stats_total_transactions_count INT,
   stats_processed_transactions_count INT,
   stats_ready_transactions_count INT,
   stats_pending_transactions_count INT,
   stats_approved_transactions_count INT,
   stats_published_transactions_count INT,
   stats_invalid_transactions_count INT,

   detail_code VARCHAR(255),
   detail_subcode VARCHAR(255),
   detail_bag jsonb,

   filtering_parameters_organisation_id VARCHAR(255) NOT NULL,
   filtering_parameters_transaction_types SMALLINT,
   filtering_parameters_transaction_numbers text[] NOT NULL DEFAULT '{}',
   filtering_parameters_from_date DATE NOT NULL,
   filtering_parameters_to_date DATE NOT NULL,
   filtering_parameters_accounting_period_from DATE, -- NULLable since sometimes when batch fails we do not have it
   filtering_parameters_accounting_period_to DATE, -- NULLable since sometimes when batch fails we do not have it

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   PRIMARY KEY (transaction_batch_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_batch_aud (
    transaction_batch_id CHAR(64) NOT NULL,

    status accounting_core_transaction_batch_status_type NOT NULL,

    stats_total_transactions_count INT,
    stats_processed_transactions_count INT,
    stats_ready_transactions_count INT,
    stats_pending_transactions_count INT,
    stats_approved_transactions_count INT,
    stats_published_transactions_count INT,
    stats_invalid_transactions_count INT,

    detail_code VARCHAR(255),
    detail_subcode VARCHAR(255),
    detail_bag JSONB,

    filtering_parameters_organisation_id VARCHAR(255) NOT NULL,
    filtering_parameters_transaction_types SMALLINT,
    filtering_parameters_transaction_numbers text[] NOT NULL DEFAULT '{}',
    filtering_parameters_from_date DATE NOT NULL,
    filtering_parameters_to_date DATE NOT NULL,
    filtering_parameters_accounting_period_from DATE,
    filtering_parameters_accounting_period_to DATE,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    -- Primary Key for the audit table
    CONSTRAINT pk_accounting_core_transaction_batch_aud PRIMARY KEY (transaction_batch_id, rev, revtype),

    -- Foreign Key to the revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction (
   transaction_id CHAR(64) NOT NULL,
   type accounting_core_transaction_type NOT NULL,
   batch_id CHAR(64) NOT NULL,
   processing_status VARCHAR(255),
   FOREIGN KEY (batch_id) REFERENCES accounting_core_transaction_batch (transaction_batch_id),

   entry_date DATE NOT NULL,
   accounting_period accounting_core_accounting_period_type NOT NULL,
   transaction_internal_number VARCHAR(255) NOT NULL,

   organisation_id CHAR(64) NOT NULL,
   organisation_name VARCHAR(255),
   organisation_country_code accounting_core_country_code_type,
   organisation_tax_id_number VARCHAR(255),
   organisation_currency_id accounting_core_currency_id_type,

   reconcilation_id CHAR(64),

   reconcilation_source accounting_core_reconcilation_accounting_core_source_type,
   reconcilation_sink accounting_core_reconcilation_accounting_core_source_type,
   reconcilation_final_status accounting_core_reconcilation_accounting_core_source_type,

   user_comment VARCHAR(255),

   automated_validation_status accounting_core_tx_validation_status_type NOT NULL,

   transaction_approved BOOLEAN NOT NULL DEFAULT FALSE,
   ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
   ledger_dispatch_status accounting_core_ledger_dispatch_status_type NOT NULL,
   primary_blockchain_type VARCHAR(255),
   primary_blockchain_hash CHAR(64),
   overall_status accounting_core_transaction_status_type NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   PRIMARY KEY (transaction_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_aud (
   transaction_id CHAR(64) NOT NULL,
   type accounting_core_transaction_type NOT NULL,
   batch_id CHAR(64) NOT NULL,
   processing_status VARCHAR(255),
   --FOREIGN KEY (batch_id) REFERENCES accounting_core_transaction_batch (transaction_batch_id),

   entry_date DATE NOT NULL,
   accounting_period accounting_core_accounting_period_type NOT NULL,
   transaction_internal_number VARCHAR(255) NOT NULL,

   organisation_id CHAR(64) NOT NULL,
   organisation_name VARCHAR(255),
   organisation_country_code accounting_core_country_code_type,
   organisation_tax_id_number VARCHAR(255),
   organisation_currency_id accounting_core_currency_id_type,

   reconcilation_id CHAR(64),

   reconcilation_source accounting_core_reconcilation_accounting_core_source_type,
   reconcilation_sink accounting_core_reconcilation_accounting_core_source_type,
   reconcilation_final_status accounting_core_reconcilation_accounting_core_source_type,

   user_comment VARCHAR(255),

   automated_validation_status accounting_core_tx_validation_status_type NOT NULL,

   transaction_approved BOOLEAN NOT NULL DEFAULT FALSE,
   ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
   ledger_dispatch_status accounting_core_ledger_dispatch_status_type NOT NULL,
   primary_blockchain_type VARCHAR(255),
   primary_blockchain_hash CHAR(64),
   overall_status accounting_core_transaction_status_type NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- special for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT NOT NULL,

   -- Primary Key Constraint
   CONSTRAINT pk_accounting_core_transaction_aud PRIMARY KEY (transaction_id, rev, revtype),

   -- Foreign Key Constraint to revinfo table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- embeddable
CREATE TABLE IF NOT EXISTS accounting_core_transaction_violation (
   transaction_id CHAR(64) NOT NULL,
   tx_item_id CHAR(64),
   code accounting_core_transaction_violation_code_type NOT NULL,

   severity accounting_core_severity_type NOT NULL,
   sub_code VARCHAR(255), -- NILLABLE
   source accounting_core_source_type NOT NULL,
   processor_module VARCHAR(255) NOT NULL,
   detail_bag jsonb NOT NULL,

   CONSTRAINT fk_accounting_core_transaction_violation FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id),

   UNIQUE (transaction_id, tx_item_id, code, sub_code)
);

-- embeddable audit table for transaction violation
CREATE TABLE IF NOT EXISTS accounting_core_transaction_violation_aud (
    transaction_id CHAR(64) NOT NULL,
    tx_item_id CHAR(64),
    code accounting_core_transaction_violation_code_type NOT NULL,

    severity accounting_core_severity_type NOT NULL,
    sub_code VARCHAR(255), -- NILLABLE
    source accounting_core_source_type NOT NULL,
    processor_module VARCHAR(255) NOT NULL,
    detail_bag JSONB NOT NULL,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,
    ord INTEGER,

    CONSTRAINT pk_accounting_core_transaction_violation_aud UNIQUE (transaction_id, tx_item_id, code, sub_code, rev, revtype),

    -- Foreign Key referencing the original transaction table
    FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION,

    -- Foreign Key to the revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_batch_assoc (
   transaction_batch_id CHAR(64) NOT NULL,
   transaction_id CHAR(64) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   FOREIGN KEY (transaction_batch_id) REFERENCES accounting_core_transaction_batch (transaction_batch_id),
   FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id),

   PRIMARY KEY (transaction_batch_id, transaction_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_batch_assoc_aud (
    transaction_batch_id CHAR(64) NOT NULL,
    transaction_id CHAR(64) NOT NULL,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,
    ord INTEGER,

    -- Primary Key for the audit table
    CONSTRAINT pk_accounting_core_transaction_batch_assoc_aud PRIMARY KEY (transaction_batch_id, transaction_id, rev, revtype),

    -- Foreign Key referencing the transaction batch table
    FOREIGN KEY (transaction_batch_id) REFERENCES accounting_core_transaction_batch (transaction_batch_id) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION,

    -- Foreign Key referencing the transaction table
    FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION,

    -- Foreign Key to the revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_item (
   transaction_item_id CHAR(64) NOT NULL,

   transaction_id CHAR(64) NOT NULL,

   FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id),

   fx_rate DECIMAL(30, 15) NOT NULL,

   rejection_reason accounting_core_rejection_reason_type,

   account_code_debit VARCHAR(255),
   account_ref_code_debit VARCHAR(255),
   account_name_debit VARCHAR(255),

   account_code_credit VARCHAR(255),
   account_ref_code_credit VARCHAR(255),
   account_name_credit VARCHAR(255),

   account_event_code VARCHAR(255),
   account_event_name VARCHAR(255),

   amount_fcy DECIMAL(30, 15) NOT NULL,
   amount_lcy DECIMAL(30, 15) NOT NULL,

   document_num VARCHAR(255),
   document_currency_customer_code VARCHAR(255),
   document_currency_id accounting_core_currency_id_type,

   document_vat_customer_code VARCHAR(255),
   document_vat_rate DECIMAL(30, 15),

   document_counterparty_customer_code VARCHAR(255),
   document_counterparty_type accounting_core_counter_party_type,
   document_counterparty_name VARCHAR(255),

   project_customer_code VARCHAR(255),
   project_external_customer_code VARCHAR(255),
   project_name VARCHAR(255),

   cost_center_customer_code VARCHAR(255),
   cost_center_external_customer_code VARCHAR(255),
   cost_center_name VARCHAR(255),

   status accounting_core_tx_item_validation_status_type NOT NULL,
   operation_type accounting_core_tx_item_operation_type NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   PRIMARY KEY (transaction_item_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_transaction_item_aud (
   transaction_item_id CHAR(64) NOT NULL,

   transaction_id CHAR(64) NOT NULL,

   --FOREIGN KEY (transaction_id) REFERENCES accounting_core_transaction (transaction_id),

   fx_rate DECIMAL(30, 15) NOT NULL,

   rejection_reason accounting_core_rejection_reason_type,

   account_code_debit VARCHAR(255),
   account_ref_code_debit VARCHAR(255),
   account_name_debit VARCHAR(255),

   account_code_credit VARCHAR(255),
   account_ref_code_credit VARCHAR(255),
   account_name_credit VARCHAR(255),

   account_event_code VARCHAR(255),
   account_event_name VARCHAR(255),

   amount_fcy DECIMAL(30, 15) NOT NULL,
   amount_lcy DECIMAL(30, 15) NOT NULL,

   document_num VARCHAR(255),
   document_currency_customer_code VARCHAR(255),
   document_currency_id accounting_core_currency_id_type,

   document_vat_customer_code VARCHAR(255),
   document_vat_rate DECIMAL(30, 15),

   document_counterparty_customer_code VARCHAR(255),
   document_counterparty_type accounting_core_counter_party_type,
   document_counterparty_name VARCHAR(255),

   project_customer_code VARCHAR(255),
   project_external_customer_code VARCHAR(255),
   project_name VARCHAR(255),

   cost_center_customer_code VARCHAR(255),
   cost_center_external_customer_code VARCHAR(255),
   cost_center_name VARCHAR(255),
   status accounting_core_tx_item_validation_status_type,
   operation_type accounting_core_tx_item_operation_type NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT NOT NULL,
   ord INTEGER,

   -- Primary Key for audit table
   CONSTRAINT pk_accounting_core_transaction_item_aud PRIMARY KEY (transaction_item_id, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS accounting_core_reconcilation (
    reconcilation_id CHAR(64) NOT NULL,
    organisation_id CHAR(64) NOT NULL,
    from_date DATE,
    to_date DATE,
    status accounting_core_reconcilation_status_type NOT NULL,

    processed_tx_count INT NOT NULL,

    detail_code VARCHAR(255),
    detail_subcode VARCHAR(255),
    detail_bag jsonb,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    PRIMARY KEY (reconcilation_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_reconcilation_aud (
    reconcilation_id CHAR(64) NOT NULL,
    organisation_id CHAR(64) NOT NULL,
    from_date DATE,
    to_date DATE,
    status accounting_core_reconcilation_status_type NOT NULL,

    processed_tx_count INT NOT NULL,

    detail_code VARCHAR(255),
    detail_subcode VARCHAR(255),
    detail_bag JSONB,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,
    ord INTEGER,

    -- Primary Key for the audit table
    CONSTRAINT pk_accounting_core_reconcilation_aud PRIMARY KEY (reconcilation_id, rev, revtype),

    -- Foreign Key to revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- embeddable
CREATE TABLE IF NOT EXISTS accounting_core_reconcilation_violation (
    reconcilation_id CHAR(64) NOT NULL,
    transaction_id CHAR(64) NOT NULL,
    rejection_code accounting_core_reconcilation_rejection_code_type NOT NULL,

    transaction_internal_number VARCHAR(255) NOT NULL,
    transaction_entry_date DATE NOT NULL,
    transaction_type accounting_core_transaction_type NOT NULL,
    amount_lcy_sum DECIMAL(30, 8) NOT NULL,

    source_diff jsonb,

    CONSTRAINT fk_accounting_core_reconcilation_violation FOREIGN KEY (reconcilation_id) REFERENCES accounting_core_reconcilation (reconcilation_id),

    PRIMARY KEY (reconcilation_id, transaction_id, rejection_code)
);

-- embeddable audit table for reconciliation violation
CREATE TABLE IF NOT EXISTS accounting_core_reconcilation_violation_aud (
    reconcilation_id CHAR(64) NOT NULL,
    transaction_id CHAR(64) NOT NULL,
    rejection_code accounting_core_reconcilation_rejection_code_type NOT NULL,
    transaction_internal_number VARCHAR(255) NOT NULL,
    transaction_entry_date DATE NOT NULL,
    transaction_type accounting_core_transaction_type NOT NULL,
    amount_lcy_sum DECIMAL(30, 8) NOT NULL,
    source_diff JSONB,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,
    ord INTEGER,

    -- Primary Key for the audit table
    CONSTRAINT pk_accounting_core_reconcilation_violation_aud PRIMARY KEY (reconcilation_id, transaction_id, rejection_code, rev, revtype),

    -- Foreign Key referencing the original reconciliation table
    FOREIGN KEY (reconcilation_id) REFERENCES accounting_core_reconcilation (reconcilation_id) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION,

    -- Foreign Key to revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE table accounting_core_report (
    report_id CHAR(64) NOT NULL,
    id_control CHAR(64) NOT NULL,
    ver BIGINT NOT NULL,
    organisation_id CHAR(64) NOT NULL,
    organisation_name VARCHAR(255) NOT NULL,
    organisation_country_code accounting_core_country_code_type NOT NULL,
    organisation_tax_id_number VARCHAR(255) NOT NULL,
    organisation_currency_id accounting_core_currency_id_type NOT NULL,

    type accounting_core_report_type NOT NULL,
    interval_type accounting_core_report_internal_type NOT NULL,
    year SMALLINT CHECK (year BETWEEN 1970 AND 4000) NOT NULL,
    period SMALLINT CHECK (period BETWEEN 1 AND 12),
    date DATE NOT NULL, -- report date

    -- USER or SYSTEM report
    mode accounting_core_report_mode_type NOT NULL,

    -- Main data fields
    -- Balance Sheet::Assets
    data_balance_sheet__assets_non_current_tangible_assets DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_intangible_assets DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_investments DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_financial_assets DECIMAL(30, 8),

    data_balance_sheet__assets_current_prepayments_short_assets DECIMAL(30, 8),
    data_balance_sheet__assets_current_other_receivables DECIMAL(30, 8),
    data_balance_sheet__assets_current_crypto_assets DECIMAL(30, 8),
    data_balance_sheet__assets_current_cash_and_equivalen DECIMAL(30, 8),

    -- Balance Sheet::Liabilities
    data_balance_sheet__liabilities_non_current_provisions DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_trade_accounts DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_other_liabilities DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_accruals_and_short DECIMAL(30, 8),

    -- Balance Sheet::Capital
    data_balance_sheet__capital_capital DECIMAL(30, 8),
    data_balance_sheet__capital_profit_for_the_year DECIMAL(30, 8),
    data_balance_sheet__capital_results_carried_forward DECIMAL(30, 8),

    -- Income Statement::Revenues
    data_income_statement__revenues_other_income DECIMAL(30, 8),
    data_income_statement__revenues_build_long_term_provision DECIMAL(30, 8),

    data_income_statement__cost_goods_and_services_external_serv DECIMAL(30, 8),

    -- Income Statement::Operating Expenses
    data_income_statement__operating_expenses_personnel_expenses DECIMAL(30, 8),
    data_income_statement__operating_expenses_general_admin_ex DECIMAL(30, 8),
    data_income_statement__operating_expenses_depreciation_tang DECIMAL(30, 8),
    data_income_statement__operating_expenses_amortization_int DECIMAL(30, 8),
    data_income_statement__operating_expenses_rent_expenses DECIMAL(30, 8),

    -- Income Statement::Financial Income
    data_income_statement__financial_income_financial_revenues DECIMAL(30, 8),
    data_income_statement__financial_income_financial_expenses DECIMAL(30, 8),
    data_income_statement__financial_income_realised_gains DECIMAL(30, 8),
    data_income_statement__financial_income_staking_rewards DECIMAL(30, 8),
    data_income_statement__financial_income_net_income_opt DECIMAL(30, 8),

    -- Income Statement::Extraordinary Income
    data_income_statement__operating_expenses_extraordin_exp DECIMAL(30, 8),

    -- Income Statement::Tax Expenses
    data_income_statement__tax_expenses_direct_taxes DECIMAL(30, 8),

    -- Calculated field by the JPA
    data_income_statement__profit_for_the_year DECIMAL(30, 8),

    -- End of main data fields

    ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ledger_dispatch_status accounting_core_ledger_dispatch_status_type NOT NULL,
    ledger_dispatch_date TIMESTAMP WITHOUT TIME ZONE,
    published_by VARCHAR(255),
    primary_blockchain_type VARCHAR(255),
    primary_blockchain_hash CHAR(64),

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    PRIMARY KEY (report_id)
);

CREATE TABLE IF NOT EXISTS accounting_core_report_aud (
    report_id CHAR(64) NOT NULL,
    id_control CHAR(64) NOT NULL,
    ver BIGINT NOT NULL,
    organisation_id CHAR(64) NOT NULL,
    organisation_name VARCHAR(255) NOT NULL,
    organisation_country_code accounting_core_country_code_type NOT NULL,
    organisation_tax_id_number VARCHAR(255) NOT NULL,
    organisation_currency_id accounting_core_currency_id_type NOT NULL,

    type accounting_core_report_type NOT NULL,
    interval_type accounting_core_report_internal_type NOT NULL,
    year SMALLINT CHECK (year BETWEEN 1970 AND 4000) NOT NULL,
    period SMALLINT CHECK (period BETWEEN 1 AND 12),
    date DATE NOT NULL, -- Report date

    mode accounting_core_report_mode_type NOT NULL, -- USER or SYSTEM report

    -- Main data fields
    -- Balance Sheet::Assets
    data_balance_sheet__assets_non_current_tangible_assets DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_intangible_assets DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_investments DECIMAL(30, 8),
    data_balance_sheet__assets_non_current_financial_assets DECIMAL(30, 8),

    data_balance_sheet__assets_current_prepayments_short_assets DECIMAL(30, 8),
    data_balance_sheet__assets_current_other_receivables DECIMAL(30, 8),
    data_balance_sheet__assets_current_crypto_assets DECIMAL(30, 8),
    data_balance_sheet__assets_current_cash_and_equivalen DECIMAL(30, 8),

    -- Balance Sheet::Liabilities
    data_balance_sheet__liabilities_non_current_provisions DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_trade_accounts DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_other_liabilities DECIMAL(30, 8),
    data_balance_sheet__liabilities_current_accruals_and_short DECIMAL(30, 8),

    -- Balance Sheet::Capital
    data_balance_sheet__capital_capital DECIMAL(30, 8),
    data_balance_sheet__capital_profit_for_the_year DECIMAL(30, 8),
    data_balance_sheet__capital_results_carried_forward DECIMAL(30, 8),

    -- Income Statement::Revenues
    data_income_statement__revenues_other_income DECIMAL(30, 8),
    data_income_statement__revenues_build_long_term_provision DECIMAL(30, 8),

    data_income_statement__cost_goods_and_services_external_serv DECIMAL(30, 8),

    -- Income Statement::Operating Expenses
    data_income_statement__operating_expenses_personnel_expenses DECIMAL(30, 8),
    data_income_statement__operating_expenses_general_admin_ex DECIMAL(30, 8),
    data_income_statement__operating_expenses_depreciation_tang DECIMAL(30, 8),
    data_income_statement__operating_expenses_amortization_int DECIMAL(30, 8),
    data_income_statement__operating_expenses_rent_expenses DECIMAL(30, 8),

    -- Income Statement::Financial Income
    data_income_statement__financial_income_financial_revenues DECIMAL(30, 8),
    data_income_statement__financial_income_financial_expenses DECIMAL(30, 8),
    data_income_statement__financial_income_realised_gains DECIMAL(30, 8),
    data_income_statement__financial_income_staking_rewards DECIMAL(30, 8),
    data_income_statement__financial_income_net_income_opt DECIMAL(30, 8),

    -- Income Statement::Extraordinary Income
    data_income_statement__operating_expenses_extraordin_exp DECIMAL(30, 8),

    -- Income Statement::Tax Expenses
    data_income_statement__tax_expenses_direct_taxes DECIMAL(30, 8),

    -- Calculated field by the JPA
    data_income_statement__profit_for_the_year DECIMAL(30, 8),

    -- End of main data fields

    ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ledger_dispatch_status accounting_core_ledger_dispatch_status_type NOT NULL,
    ledger_dispatch_date TIMESTAMP WITHOUT TIME ZONE,
    published_by VARCHAR(255),
    primary_blockchain_type VARCHAR(255),
    primary_blockchain_hash CHAR(64),

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,
    ord INTEGER,

    -- Primary Key for the audit table
    CONSTRAINT pk_accounting_core_report_aud PRIMARY KEY (report_id, rev, revtype),

    -- Foreign Key to revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS accounting_core_charts (
  id BIGSERIAL PRIMARY KEY,
  dashboard_id BIGINT NOT NULL REFERENCES accounting_core_dashboard(id) ON DELETE CASCADE,
  x_pos DOUBLE PRECISION,
  y_pos DOUBLE PRECISION,
  width DOUBLE PRECISION,
  height DOUBLE PRECISION,
  metric accounting_core_metric_type NOT NULL,
  sub_metric account_core_submetric_type NOT NULL
);

-- indices

-- Indexes for JSONB columns
CREATE INDEX idx_transaction_violation_bag_gin
ON accounting_core_transaction_violation USING GIN (detail_bag);

-- Indexes for JSONB columns
CREATE INDEX idx_transaction_batch_bag_gin
ON accounting_core_transaction_batch USING GIN (detail_bag);

-- Indexes for JSONB columns
CREATE INDEX idx_accounting_core_reconcilation_bag_gin
ON accounting_core_reconcilation USING GIN (detail_bag);

-- Indexes for text array
CREATE INDEX idx_transaction_batch_filtering_transaction_numbers
ON accounting_core_transaction_batch USING GIN (filtering_parameters_transaction_numbers);

-- AccountingCoreTransactionRepository.findDispatchableTransactions
CREATE INDEX idx_transaction_dispatchable
ON accounting_core_transaction (organisation_id, overall_status, ledger_dispatch_status, created_at, transaction_id);

-- AccountingCoreTransactionRepository.findByEntryDateRangeAndNotReconciledYet
CREATE INDEX idx_transaction_reconciliation_status
ON accounting_core_transaction (organisation_id, entry_date, reconcilation_source, reconcilation_sink, created_at, transaction_id);

-- CustomTransactionRepository.findAllByStatus
CREATE INDEX idx_transaction_organisation_status
ON accounting_core_transaction (organisation_id, automated_validation_status);

CREATE INDEX idx_transaction_reconcilation_id
ON accounting_core_transaction (reconcilation_id);

-- CustomTransactionBatchRepositoryImpl.findByFilter
CREATE INDEX idx_reconcilation_violation_source
ON accounting_core_transaction (reconcilation_source);

-- CustomTransactionBatchRepositoryImpl.findByFilter
CREATE INDEX idx_transaction_status
ON accounting_core_transaction (overall_status);

-- CustomTransactionBatchRepositoryImpl.queryCriteria
CREATE INDEX idx_transaction_approval_dispatch_validation_status
ON accounting_core_transaction (transaction_approved, ledger_dispatch_approved, automated_validation_status);

-- CustomTransactionBatchRepositoryImpl.findByFilter
CREATE INDEX idx_transaction_batch
ON accounting_core_transaction_batch (filtering_parameters_organisation_id, filtering_parameters_transaction_types, created_at);

-- AccountingCoreTransactionRepository.findAllByTxId
CREATE INDEX idx_transaction_batch_assoc_transaction_id
ON accounting_core_transaction_batch_assoc (transaction_id);

-- TransactionItemRepository.findByTxIdAndItemId
CREATE INDEX idx_transaction_item_txid_txitemid
ON accounting_core_transaction_item (transaction_id, transaction_item_id, rejection_reason);

-- CustomTransactionRepository.findAllReconciliation
CREATE INDEX idx_reconcilation_final_status
ON accounting_core_reconcilation (status);

-- CustomTransactionRepository.findAllReconciliationSpecial
CREATE INDEX idx_reconcilation_violation_rejection_code
ON accounting_core_reconcilation_violation (rejection_code);

CREATE INDEX idx_reconcilation_violation_transaction_id
ON accounting_core_reconcilation_violation (transaction_id);

-- ReportRepository.findDispatchableTransactions
CREATE INDEX idx_report_dispatch_status_approved_order
ON accounting_core_report (organisation_id, ledger_dispatch_status, ledger_dispatch_approved, created_at, report_id);
