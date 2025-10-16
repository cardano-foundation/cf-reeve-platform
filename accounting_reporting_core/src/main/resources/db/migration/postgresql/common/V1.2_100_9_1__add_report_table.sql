
CREATE table accounting_core_report_dynamic (
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


    ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
    is_ready_to_publish BOOLEAN NOT NULL DEFAULT FALSE,
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

CREATE TABLE IF NOT EXISTS accounting_core_report_dynamic_aud (
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

    ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
    is_ready_to_publish BOOLEAN NOT NULL DEFAULT FALSE,
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
    CONSTRAINT pk_accounting_core_report_dynamic_aud PRIMARY KEY (report_id, rev, revtype),

    -- Foreign Key to revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE table accounting_core_report_dynamic_field (
  id CHAR(64) NOT NULL,
  report_id CHAR(64) NOT NULL,
  amount VARCHAR(255),
  field_id VARCHAR(255),
   created_by VARCHAR(255),
      updated_by VARCHAR(255),
      created_at TIMESTAMP WITHOUT TIME ZONE,
      updated_at TIMESTAMP WITHOUT TIME ZONE,

          PRIMARY KEY (id)

);

CREATE table accounting_core_report_dynamic_field_aud (
  id CHAR(64) NOT NULL,
  report_id CHAR(64) NOT NULL,
  amount VARCHAR(255),
  field_id VARCHAR(255),
   created_by VARCHAR(255),
      updated_by VARCHAR(255),
      created_at TIMESTAMP WITHOUT TIME ZONE,
      updated_at TIMESTAMP WITHOUT TIME ZONE,
       -- Special columns for audit tables
          rev INTEGER NOT NULL,
          revtype SMALLINT NOT NULL,
          ord INTEGER,

          -- Primary Key for the audit table
          CONSTRAINT pk_accounting_core_report_dynamic_field_aud PRIMARY KEY (id, rev, revtype),

          -- Foreign Key to revision information table
          FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
          ON UPDATE NO ACTION ON DELETE NO ACTION
);

ALTER TYPE accounting_core_report_type ADD VALUE 'DYNAMIC';

