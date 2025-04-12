--CREATE SEQUENCE  IF NOT EXISTS netsuite_adapter_ingestion START WITH 1 INCREMENT BY 1;

CREATE TYPE code_mapping_type AS ENUM (
    'ORGANISATION',
    'VAT',
    'PROJECT',
    'CURRENCY',
    'COST_CENTER',
    'CHART_OF_ACCOUNT'
);

CREATE TABLE netsuite_adapter_ingestion (
   id CHAR(64) NOT NULL,
   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,
   ingestion_body TEXT NOT NULL,
   ingestion_body_debug TEXT,
   adapter_instance_id VARCHAR(255) NOT NULL,
   ingestion_body_checksum VARCHAR(255) NOT NULL,
   CONSTRAINT pk_netsuite_adapter_ingestion PRIMARY KEY (id)
);

CREATE TABLE netsuite_adapter_ingestion_body(
    id CHAR(64) NOT NULL,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    ingestion_body TEXT NOT NULL,
    ingestion_body_debug TEXT,
    ingestion_body_checksum VARCHAR(255) NOT NULL,
    netsuite_ingestion_id CHAR(64) NOT NULL,
    CONSTRAINT pk_netsuite_adapter_ingestion_body PRIMARY KEY (id),
    CONSTRAINT fk_netsuite_adapter_ingestion_body FOREIGN KEY (netsuite_ingestion_id)
        REFERENCES netsuite_adapter_ingestion (id) ON DELETE CASCADE
);

CREATE TABLE netsuite_adapter_code_mapping (
   mapping_id CHAR(64) NOT NULL,
   internal_id BIGINT NOT NULL,
   code_type code_mapping_type NOT NULL,
   customer_code VARCHAR(255) NOT NULL,

   created_by VARCHAR(255),
   updated_by VARCHAR(255),
   created_at TIMESTAMP WITHOUT TIME ZONE,
   updated_at TIMESTAMP WITHOUT TIME ZONE,

   CONSTRAINT pk_netsuite_adapter_code_mapping PRIMARY KEY (mapping_id, internal_id, code_type)
);
