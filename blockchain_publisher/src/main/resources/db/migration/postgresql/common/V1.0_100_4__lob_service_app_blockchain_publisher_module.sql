CREATE TYPE blockchain_publisher_blockchain_publish_status_type AS ENUM (
    'STORED',              -- transaction is stored
    'SUBMITTED',           -- submitted and sitting in the mem pool for now
    'VISIBLE_ON_CHAIN',    -- confirmed to be visible on-chain
    'COMPLETED',           -- confirmed on-chain and e.g. 5 - 40 blocks passed
    'ROLLBACKED',          -- signal to resubmit the transaction since it disappeared from on-chain
    'FINALIZED'            -- finalized on blockchain(s) - tx hash (12 hours)
);

CREATE TYPE blockchain_publisher_finality_score_type AS ENUM (
    'VERY_LOW',
    'LOW',
    'MEDIUM',
    'HIGH',
    'VERY_HIGH',
    'ULTRA_HIGH',
    'FINAL'
);

CREATE TYPE blockchain_publisher_counter_party_type AS ENUM (
    'EMPLOYEE',
    'VENDOR',
    'DONOR',
    'CLIENT'
);

CREATE TYPE blockchain_publisher_transaction_type AS ENUM (
    'CardCharge',
    'VendorBill',
    'CardRefund',
    'Journal',
    'FxRevaluation',
    'Transfer',
    'CustomerPayment',
    'ExpenseReport',
    'VendorPayment',
    'BillCredit'
);

-- ISO_4217:CHF or ISO_24165:BSV:2L8HS2MNP, ISO_24165:ADA:HWGL1C2CK, etc
CREATE DOMAIN blockchain_publisher_currency_id_type AS VARCHAR(25)
    CHECK (VALUE ~ '^(ISO_4217:[A-Z]{3})|(ISO_24165:[A-Z]{3}:[A-Z0-9]+)$');

-- ISO 3166-1 alpha-2 country code, e.g. CH, DE, US, etc
CREATE DOMAIN blockchain_publisher_country_code_type AS CHAR(2)
    CHECK (VALUE ~ '^[A-Z]{2}$');

CREATE DOMAIN blockchain_publisher_accounting_period_type AS CHAR(7)
    CHECK (VALUE ~ '^[0-9]{4}-[0-9]{2}$');

CREATE TABLE blockchain_publisher_transaction (
   transaction_id CHAR(64) NOT NULL,
   organisation_id CHAR(64) NOT NULL,
   internal_number VARCHAR(255) NOT NULL,
   batch_id CHAR(64) NOT NULL,

   organisation_name VARCHAR(255) NOT NULL,
   organisation_tax_id_number VARCHAR(255) NOT NULL,
   organisation_country_code blockchain_publisher_country_code_type NOT NULL,
   organisation_currency_id blockchain_publisher_currency_id_type NOT NULL,

   type blockchain_publisher_transaction_type NOT NULL,
   accounting_period blockchain_publisher_accounting_period_type NOT NULL,
   entry_date DATE NOT NULL,

   l1_transaction_hash CHAR(64),
   l1_absolute_slot BIGINT,
   l1_creation_slot BIGINT,
   l1_publish_status blockchain_publisher_blockchain_publish_status_type,
   l1_finality_score blockchain_publisher_finality_score_type,

   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,
   locked_at TIMESTAMP WITHOUT TIME ZONE,

   PRIMARY KEY (transaction_id)
);

CREATE TABLE blockchain_publisher_transaction_item (
   transaction_item_id CHAR(64) NOT NULL,
   transaction_id CHAR(64) NOT NULL,

   FOREIGN KEY (transaction_id) REFERENCES blockchain_publisher_transaction (transaction_id),

   fx_rate DECIMAL(12, 8) NOT NULL,

   amount_fcy DECIMAL(30, 8) NOT NULL,

   account_event_code VARCHAR(255) NOT NULL,
   account_event_name VARCHAR(255) NOT NULL,

   project_customer_code VARCHAR(255),
   project_name VARCHAR(255),

   cost_center_customer_code VARCHAR(255),
   cost_center_name VARCHAR(255),

   document_num VARCHAR(255),
   document_currency_id blockchain_publisher_currency_id_type,
   document_currency_customer_code VARCHAR(255),
   document_counterparty_customer_code VARCHAR(255),
   document_counterparty_type blockchain_publisher_counter_party_type,

   document_vat_customer_code VARCHAR(255),
   document_vat_rate DECIMAL(12, 8),

   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   PRIMARY KEY (transaction_item_id)
);

CREATE TABLE blockchain_publisher_report (
    report_id VARCHAR(64) NOT NULL PRIMARY KEY,
    ver BIGINT NOT NULL,
    organisation_id VARCHAR(64),
    organisation_name VARCHAR(255),
    organisation_country_code VARCHAR(3),
    organisation_tax_id_number VARCHAR(64),
    organisation_currency_id VARCHAR(64),
    type VARCHAR(255) NOT NULL,
    interval_type VARCHAR(255) NOT NULL,
    year SMALLINT NOT NULL CHECK (year >= 1900 AND year <= 4000),
    period SMALLINT CHECK (period BETWEEN 1 AND 12),
    mode VARCHAR(255) NOT NULL,
    date DATE NOT NULL,

    data_balance_sheet__assets_non_current_tangible_assets NUMERIC,
    data_balance_sheet__assets_non_current_intangible_assets NUMERIC,
    data_balance_sheet__assets_non_current_investments NUMERIC,
    data_balance_sheet__assets_non_current_financial_assets NUMERIC,
    data_balance_sheet__assets_current_prepayments_short_assets NUMERIC,
    data_balance_sheet__assets_current_other_receivables NUMERIC,
    data_balance_sheet__assets_current_crypto_assets NUMERIC,
    data_balance_sheet__assets_current_cash_and_equivalen NUMERIC,
    data_balance_sheet__liabilities_non_current_provisions NUMERIC,
    data_balance_sheet__liabilities_current_trade_accounts NUMERIC,
    data_balance_sheet__liabilities_current_other_liabilities NUMERIC,
    data_balance_sheet__liabilities_current_accruals_and_short NUMERIC,
    data_balance_sheet__capital_capital NUMERIC,
    data_balance_sheet__capital_profit_for_the_year NUMERIC,
    data_balance_sheet__capital_results_carried_forward NUMERIC,
    data_income_statement__revenues_other_income NUMERIC,
    data_income_statement__revenues_build_long_term_provision NUMERIC,
    data_income_statement__cost_goods_and_services_external_serv NUMERIC,
    data_income_statement__operating_expenses_personnel_expenses NUMERIC,
    data_income_statement__operating_expenses_general_admin_ex NUMERIC,
    data_income_statement__operating_expenses_depreciation_tang NUMERIC,
    data_income_statement__operating_expenses_amortization_int NUMERIC,
    data_income_statement__operating_expenses_rent_expenses NUMERIC,
    data_income_statement__financial_income_financial_revenues NUMERIC,
    data_income_statement__financial_income_financial_expenses NUMERIC,
    data_income_statement__financial_income_realised_gains NUMERIC,
    data_income_statement__financial_income_staking_rewards NUMERIC,
    data_income_statement__financial_income_net_income_opt NUMERIC,
    data_income_statement__operating_expenses_extraordin_exp NUMERIC,
    data_income_statement__tax_expenses_direct_taxes NUMERIC,
    data_income_statement__profit_for_the_year NUMERIC,

    l1_transaction_hash CHAR(64),
    l1_absolute_slot BIGINT,
    l1_creation_slot BIGINT,
    l1_publish_status blockchain_publisher_blockchain_publish_status_type,
    l1_finality_score blockchain_publisher_finality_score_type,

    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- TODO mczeladka: adding indices for blockchain_publisher_report depends on read queries we have...

-- blockchain_publisher.TransactionEntity.findTransactionsByStatus
CREATE INDEX idx_transaction_entity_publish_status
ON blockchain_publisher_transaction (organisation_id, l1_publish_status, created_at, transaction_id);
