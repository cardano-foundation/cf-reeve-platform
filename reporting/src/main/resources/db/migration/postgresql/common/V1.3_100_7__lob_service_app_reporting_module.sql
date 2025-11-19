-- Reporting Module - Initial Schema

-- Report Template Table
CREATE TABLE IF NOT EXISTS report_template (
    id BIGSERIAL NOT NULL,
    organisation_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    currency_id VARCHAR(255),
    ver BIGINT NOT NULL DEFAULT 1,
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_template PRIMARY KEY (id)
);

CREATE INDEX idx_report_template_organisation_id ON report_template(organisation_id);
CREATE INDEX idx_report_template_organisation_name ON report_template(organisation_id, name);

-- Report Template Audit Table
CREATE TABLE IF NOT EXISTS report_template_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    
    organisation_id VARCHAR(64),
    name VARCHAR(255),
    description TEXT,
    currency_id VARCHAR(255),
    ver BIGINT NOT NULL DEFAULT 1,
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_template_aud PRIMARY KEY (id, rev)
);

-- Report Template field Table
CREATE TABLE IF NOT EXISTS report_template_field (
    id BIGSERIAL NOT NULL,
    report_template_id BIGINT NOT NULL,
    parent_field_id BIGINT,
    name VARCHAR(255) NOT NULL,
    accumulated BOOLEAN NOT NULL DEFAULT FALSE,
    accumulated_yearly BOOLEAN NOT NULL DEFAULT FALSE,
    accumulated_previous_year BOOLEAN NOT NULL DEFAULT FALSE,
    negated BOOLEAN NOT NULL DEFAULT FALSE,
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_template_field PRIMARY KEY (id),
    CONSTRAINT fk_report_template_field_template FOREIGN KEY (report_template_id) 
        REFERENCES report_template(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_template_field_parent FOREIGN KEY (parent_field_id) 
        REFERENCES report_template_field(id) ON DELETE CASCADE
);

CREATE INDEX idx_report_template_field_template_id ON report_template_field(report_template_id);
CREATE INDEX idx_report_template_field_parent_id ON report_template_field(parent_field_id);

-- Report Template field Audit Table
CREATE TABLE IF NOT EXISTS report_template_field_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    
    report_template_id BIGINT,
    parent_field_id BIGINT,
    name VARCHAR(255),
    accumulated BOOLEAN,
    accumulated_previous_year BOOLEAN,
    negated BOOLEAN,
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_template_field_aud PRIMARY KEY (id, rev)
);

-- Organisation Report Setup Field SubType Mapping Table (Many-to-Many)
CREATE TABLE IF NOT EXISTS reporting_field_subtype_mapping (
    field_id BIGINT NOT NULL,
    sub_type_id BIGINT NOT NULL,
    
    CONSTRAINT pk_reporting_field_subtype_mapping PRIMARY KEY (field_id, sub_type_id),
    CONSTRAINT fk_reporting_field_mapping_field FOREIGN KEY (field_id) 
        REFERENCES report_template_field(id) ON DELETE CASCADE,
    CONSTRAINT fk_reporting_field_mapping_subtype FOREIGN KEY (sub_type_id) 
        REFERENCES organisation_chart_of_account_sub_type(id) ON DELETE CASCADE
);

CREATE INDEX idx_reporting_field_subtype_mapping_field_id ON reporting_field_subtype_mapping(field_id);
CREATE INDEX idx_reporting_field_subtype_mapping_subtype_id ON reporting_field_subtype_mapping(sub_type_id);

-- Report Table
CREATE TABLE IF NOT EXISTS report (
    id BIGSERIAL NOT NULL,
    report_template_id BIGINT NOT NULL,
    ver BIGINT NOT NULL DEFAULT 1,
    organisation_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    interval_type VARCHAR(255),
    period SMALLINT,
    year SMALLINT,
    is_ready_to_publish BOOLEAN NOT NULL DEFAULT FALSE,
    publish_error VARCHAR(255),
    ledger_dispatch_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ledger_dispatch_status VARCHAR(255) NOT NULL DEFAULT 'NOT_DISPATCHED',
    ledger_dispatch_status_error_reason TEXT,
    ledger_dispatch_date TIMESTAMP WITHOUT TIME ZONE,
    published_by VARCHAR(255),
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report PRIMARY KEY (id),
    CONSTRAINT fk_report_template FOREIGN KEY (report_template_id) 
        REFERENCES report_template(id)
);

CREATE INDEX idx_report_template_id ON report(report_template_id);
CREATE INDEX idx_report_organisation_id ON report(organisation_id);
CREATE INDEX idx_report_organisation_year ON report(organisation_id, year);
CREATE INDEX idx_report_organisation_interval ON report(organisation_id, interval_type);
CREATE INDEX idx_report_ledger_dispatch_status ON report(ledger_dispatch_status);

-- Report Audit Table
CREATE TABLE IF NOT EXISTS report_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    
    report_template_id BIGINT,
    ver BIGINT,
    organisation_id VARCHAR(64),
    name VARCHAR(255),
    interval_type VARCHAR(255),
    period SMALLINT,
    year SMALLINT,
    is_ready_to_publish BOOLEAN,
    publish_error VARCHAR(255),
    ledger_dispatch_approved BOOLEAN,
    ledger_dispatch_status VARCHAR(255),
    ledger_dispatch_status_error_reason TEXT,
    ledger_dispatch_date TIMESTAMP WITHOUT TIME ZONE,
    published_by VARCHAR(255),
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_aud PRIMARY KEY (id, rev)
);

-- Report field Table
CREATE TABLE IF NOT EXISTS report_field (
    id BIGSERIAL NOT NULL,
    report_id BIGINT NOT NULL,
    field_template_id BIGINT,
    parent_field_id BIGINT,
    value DECIMAL(19, 4),
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_field PRIMARY KEY (id),
    CONSTRAINT fk_report_field_report FOREIGN KEY (report_id) 
        REFERENCES report(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_field_template FOREIGN KEY (field_template_id) 
        REFERENCES report_template_field(id),
    CONSTRAINT fk_report_field_parent FOREIGN KEY (parent_field_id) 
        REFERENCES report_field(id) ON DELETE CASCADE
);

CREATE INDEX idx_report_field_report_id ON report_field(report_id);
CREATE INDEX idx_report_field_template_id ON report_field(field_template_id);
CREATE INDEX idx_report_field_parent_id ON report_field(parent_field_id);

-- Report field Audit Table
CREATE TABLE IF NOT EXISTS report_field_aud (
    id BIGINT NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    
    report_id BIGINT,
    field_template_id BIGINT,
    parent_field_id BIGINT,
    value DECIMAL(19, 4),
    
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    
    CONSTRAINT pk_report_field_aud PRIMARY KEY (id, rev)
);