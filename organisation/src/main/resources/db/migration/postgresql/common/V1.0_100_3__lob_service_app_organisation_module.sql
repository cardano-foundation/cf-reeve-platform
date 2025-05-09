CREATE TABLE IF NOT EXISTS organisation (
   organisation_id CHAR(64) NOT NULL,
   name VARCHAR(255) NOT NULL,
   tax_id_number VARCHAR(255) NOT NULL,
   country_code VARCHAR(2) NOT NULL,
   dummy_account VARCHAR(255),
   accounting_period_days INT NOT NULL,
   currency_id VARCHAR(255) NOT NULL,
   report_currency_id VARCHAR(255) NOT NULL,
   website_url VARCHAR(255),
   phone_number VARCHAR(255) NOT NULL,
   city VARCHAR(255) NOT NULL,
   post_code VARCHAR(255) NOT NULL,
   province VARCHAR(255) NOT NULL,
   address VARCHAR(255) NOT NULL,
   admin_email VARCHAR(255) NOT NULL,
   pre_approve_transactions BOOLEAN,
   pre_approve_transactions_dispatch BOOLEAN,
   logo text,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation PRIMARY KEY (organisation_id)
);

CREATE TABLE IF NOT EXISTS organisation_aud (
   organisation_id CHAR(64) NOT NULL,
   name VARCHAR(255) NOT NULL,
   tax_id_number VARCHAR(255) NOT NULL,
   country_code VARCHAR(2) NOT NULL,
   dummy_account VARCHAR(255),
   accounting_period_days INT NOT NULL,
   currency_id VARCHAR(255) NOT NULL,
   report_currency_id VARCHAR(255) NOT NULL,
   website_url VARCHAR(255),
   phone_number VARCHAR(255) NOT NULL,
   city VARCHAR(255) NOT NULL,
   post_code VARCHAR(255) NOT NULL,
   province VARCHAR(255) NOT NULL,
   address VARCHAR(255) NOT NULL,
   admin_email VARCHAR(255) NOT NULL,
   pre_approve_transactions BOOLEAN,
   pre_approve_transactions_dispatch BOOLEAN,
   logo TEXT,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_aud PRIMARY KEY (organisation_id, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS organisation_currency (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   currency_id VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_currency PRIMARY KEY (organisation_id, customer_code)
);

CREATE TABLE IF NOT EXISTS organisation_currency_aud (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   currency_id VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_currency_aud PRIMARY KEY (organisation_id, customer_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS organisation_vat (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   rate DECIMAL(12, 8) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_vat PRIMARY KEY (organisation_id, customer_code)
);

CREATE TABLE IF NOT EXISTS organisation_vat_aud (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   rate DECIMAL(12, 8) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_vat_aud PRIMARY KEY (organisation_id, customer_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS organisation_cost_center (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   external_customer_code VARCHAR(255) NOT NULL,
   parent_customer_code VARCHAR(255),
   name VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_cost_center PRIMARY KEY (organisation_id, customer_code)
);

CREATE TABLE IF NOT EXISTS organisation_cost_center_aud (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   external_customer_code VARCHAR(255) NOT NULL,
   parent_customer_code VARCHAR(255),
   name VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_cost_center_aud PRIMARY KEY (organisation_id, customer_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS organisation_project (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   external_customer_code VARCHAR(255) NOT NULL,
   parent_customer_code VARCHAR(255),
   name VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_project PRIMARY KEY (organisation_id, customer_code)
);

CREATE TABLE IF NOT EXISTS organisation_project_aud (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   external_customer_code VARCHAR(255) NOT NULL,
   parent_customer_code VARCHAR(255),
   name VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_project_aud PRIMARY KEY (organisation_id, customer_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE IF NOT EXISTS organisation_chart_of_account (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   ref_code VARCHAR(255) NOT NULL,
   event_ref_code VARCHAR(255) NOT NULL,
   name VARCHAR(255) NOT NULL,
   sub_type BIGINT,
   currency_id VARCHAR(255),
   counter_party VARCHAR(255),
   parent_customer_code VARCHAR(255),
   active BOOLEAN,

   opening_balance_balance_fcy DECIMAL(18, 2),
   opening_balance_balance_lcy DECIMAL(18, 2),
   opening_balance_original_currency_id_fcy VARCHAR(255),
   opening_balance_original_currency_id_lcy VARCHAR(255),
   opening_balance_balance_type VARCHAR(255),
   opening_balance_date VARCHAR(255),

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_chart_of_account PRIMARY KEY (organisation_id, customer_code)
);

CREATE TABLE IF NOT EXISTS organisation_chart_of_account_aud (
   organisation_id CHAR(64) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   ref_code VARCHAR(255) NOT NULL,
   event_ref_code VARCHAR(255) NOT NULL,
   name VARCHAR(255) NOT NULL,
   sub_type VARCHAR(255) NOT NULL,
   currency_id VARCHAR(255),
   counter_party VARCHAR(255),
   parent_customer_code VARCHAR(255),
   active BOOLEAN,

   opening_balance_balance_fcy DECIMAL(18, 2),
   opening_balance_balance_lcy DECIMAL(18, 2),
   opening_balance_original_currency_id_fcy VARCHAR(255),
   opening_balance_original_currency_id_lcy VARCHAR(255),
   opening_balance_balance_type VARCHAR(255),
   opening_balance_date VARCHAR(255),

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_chart_of_account_aud PRIMARY KEY (organisation_id, customer_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

 CREATE TABLE IF NOT EXISTS organisation_chart_of_account_type (
    id BIGSERIAL PRIMARY KEY,
    organisation_id CHAR(64) NOT NULL,
    name VARCHAR(255),


    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE

 );

CREATE TABLE IF NOT EXISTS organisation_chart_of_account_type_aud (
        id CHAR(255),
       organisation_id CHAR(64) NOT NULL,
       name VARCHAR(255),

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_chart_of_account_type_aud PRIMARY KEY (id, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

 CREATE TABLE IF NOT EXISTS organisation_chart_of_account_sub_type (
    id BIGSERIAL PRIMARY KEY,
    organisation_id CHAR(64) NOT NULL,
    name VARCHAR(255),
    type BIGINT NOT NULL,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE

 );

CREATE TABLE IF NOT EXISTS organisation_chart_of_account_sub_type_aud (
   id CHAR(255),
   organisation_id CHAR(64) NOT NULL,
   name VARCHAR(255),
   type BIGINT NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_chart_of_account_sub_type_aud PRIMARY KEY (id, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);


 CREATE TABLE IF NOT EXISTS organisation_ref_codes (
    organisation_id CHAR(64) NOT NULL,
    reference_code VARCHAR(255) NOT NULL,
    name VARCHAR(255),
    parent_reference_code VARCHAR(255),
    active BOOLEAN,

    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_ref_codes PRIMARY KEY (organisation_id, reference_code)

 );

CREATE TABLE IF NOT EXISTS organisation_ref_codes_aud (
       organisation_id CHAR(64) NOT NULL,
       reference_code VARCHAR(255) NOT NULL,
       name VARCHAR(255),
       parent_reference_code VARCHAR(255),
       active BOOLEAN,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_ref_codes_aud PRIMARY KEY (organisation_id, reference_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);


CREATE TABLE IF NOT EXISTS organisation_account_event (
   organisation_id CHAR(64) NOT NULL,
   debit_reference_code VARCHAR(255) NOT NULL,
   credit_reference_code VARCHAR(255) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   name VARCHAR(255) NOT NULL,
   active BOOLEAN,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_organisation_account_event PRIMARY KEY (organisation_id, debit_reference_code,credit_reference_code),
   FOREIGN KEY (organisation_id, debit_reference_code)
              REFERENCES organisation_ref_codes(organisation_id, reference_code),
          FOREIGN KEY (organisation_id, credit_reference_code)
              REFERENCES organisation_ref_codes(organisation_id, reference_code)
);

CREATE TABLE IF NOT EXISTS organisation_account_event_aud (
   organisation_id CHAR(64) NOT NULL,
   debit_reference_code VARCHAR(255) NOT NULL,
   credit_reference_code VARCHAR(255) NOT NULL,
   customer_code VARCHAR(255) NOT NULL,
   name VARCHAR(255) NOT NULL,
   active BOOLEAN,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT,

   -- Primary Key for the audit table
   CONSTRAINT pk_organisation_account_event_aud PRIMARY KEY (organisation_id, debit_reference_code, credit_reference_code, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE organisation_report_setup (
    id BIGINT PRIMARY KEY,
    organisation_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE organisation_report_setup_aud (
    id BIGINT,
    organisation_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT,

    -- Primary Key for the audit table
    CONSTRAINT pk_organisation_report_setup_aud PRIMARY KEY (id, rev, revtype)
);

CREATE TABLE organisation_report_setup_field (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    accumulated BOOLEAN NOT NULL,
    accumulated_yearly BOOLEAN NOT NULL,
    report_id BIGINT,
    parent_id BIGINT,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    FOREIGN KEY (report_id)
        REFERENCES organisation_report_setup(id)
     ON DELETE CASCADE
);

CREATE TABLE organisation_report_setup_field_aud (
    id BIGINT,
    name VARCHAR(255) NOT NULL,
    accumulated BOOLEAN NOT NULL,
    accumulated_yearly BOOLEAN NOT NULL,
    report_id BIGINT,
    parent_id BIGINT,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    FOREIGN KEY (report_id)
     REFERENCES organisation_report_setup(id)
     ON DELETE CASCADE,
    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT,

    -- Primary Key for the audit table
    CONSTRAINT pk_organisation_report_setup_field_aud PRIMARY KEY (id)
);

CREATE TABLE organisation_report_setup_field_subtype_mapping (
    field_id BIGINT NOT NULL,
    sub_type_id BIGINT NOT NULL,
    PRIMARY KEY (field_id, sub_type_id),
    FOREIGN KEY (field_id) REFERENCES organisation_report_setup_field(id) ON DELETE CASCADE,
    FOREIGN KEY (sub_type_id) REFERENCES organisation_chart_of_account_sub_type(id) ON DELETE CASCADE
);

CREATE TABLE organisation_report_setup_field_subtype_mapping_aud (
    field_id BIGINT NOT NULL,
    sub_type_id BIGINT NOT NULL,
    FOREIGN KEY (field_id) REFERENCES organisation_report_setup_field(id) ON DELETE CASCADE,
    FOREIGN KEY (sub_type_id) REFERENCES organisation_chart_of_account_sub_type(id) ON DELETE CASCADE,

    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT,

    -- Primary Key for the audit table
    CONSTRAINT pk_organisation_setup_field_subtype_mapping_aud PRIMARY KEY (field_id, sub_type_id)
);