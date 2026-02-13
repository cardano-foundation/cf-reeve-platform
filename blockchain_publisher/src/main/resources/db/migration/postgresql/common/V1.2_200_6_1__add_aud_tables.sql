CREATE TABLE blockchain_publisher_transaction_aud (
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

    l1_publish_status_error_reason TEXT,
    l1_publish_retry SMALLINT,
    -- Special columns for audit tables
    rev INTEGER NOT NULL,
    revtype SMALLINT NOT NULL,

    -- Primary Key for the audit table
    CONSTRAINT pk_blockchain_publisher_transaction_aud PRIMARY KEY (transaction_id, rev, revtype),

    -- Foreign Key to the revision information table
    FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
    ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE TABLE blockchain_publisher_transaction_item_aud (
   transaction_item_id CHAR(64) NOT NULL,
   transaction_id CHAR(64) NOT NULL,

   -- FOREIGN KEY (transaction_id) REFERENCES blockchain_publisher_transaction (transaction_id),

   fx_rate DECIMAL(30, 15) NOT NULL,

   amount_fcy DECIMAL(30, 15) NOT NULL,
   amount_lcy DECIMAL(30, 15),
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
   document_vat_rate DECIMAL(30, 15),

   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   -- Special columns for audit tables
   rev INTEGER NOT NULL,
   revtype SMALLINT NOT NULL,

   -- Primary Key for the audit table
   CONSTRAINT pk_blockchain_publisher_transaction_item_aud PRIMARY KEY (transaction_item_id, rev, revtype),

   -- Foreign Key to the revision information table
   FOREIGN KEY (rev) REFERENCES revinfo (rev) MATCH SIMPLE
   ON UPDATE NO ACTION ON DELETE NO ACTION
);