-- Create new blockchain_publisher_report_v2 table for flexible report structure

CREATE TABLE blockchain_publisher_report_v2 (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    organisation_id VARCHAR(64) NOT NULL,
    report_template_type VARCHAR(50) NOT NULL,
    report_template_ver BIGINT NOT NULL,
    report_ver BIGINT NOT NULL,
    interval_type VARCHAR(255) NOT NULL,
    period SMALLINT CHECK (period BETWEEN 1 AND 12),
    year SMALLINT NOT NULL CHECK (year >= 1900 AND year <= 4000),
    data_mode VARCHAR(20) NOT NULL,
    report_data JSONB,

    l1_transaction_hash CHAR(64),
    l1_absolute_slot BIGINT,
    l1_creation_slot BIGINT,
    l1_publish_status blockchain_publisher_blockchain_publish_status_type,
    l1_publish_status_error_reason TEXT,
    l1_publish_retry SMALLINT,
    l1_finality_score blockchain_publisher_finality_score_type,

    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- Add comments
COMMENT ON TABLE blockchain_publisher_report_v2 IS 'Blockchain publisher reports with flexible JSON structure (V2)';
COMMENT ON COLUMN blockchain_publisher_report_v2.id IS 'Report ID (hash-based from reporting module)';
COMMENT ON COLUMN blockchain_publisher_report_v2.organisation_id IS 'Organisation ID';
COMMENT ON COLUMN blockchain_publisher_report_v2.report_template_type IS 'Report template type (BALANCE_SHEET, INCOME_STATEMENT, etc.)';
COMMENT ON COLUMN blockchain_publisher_report_v2.report_template_ver IS 'Report template version number';
COMMENT ON COLUMN blockchain_publisher_report_v2.report_ver IS 'Report version number for the same period';
COMMENT ON COLUMN blockchain_publisher_report_v2.interval_type IS 'Reporting interval type (MONTHLY, QUARTERLY, YEARLY)';
COMMENT ON COLUMN blockchain_publisher_report_v2.period IS 'Period number within the year';
COMMENT ON COLUMN blockchain_publisher_report_v2.year IS 'Reporting year';
COMMENT ON COLUMN blockchain_publisher_report_v2.data_mode IS 'Data mode (GENERATED or USER)';
COMMENT ON COLUMN blockchain_publisher_report_v2.report_data IS 'Report data as JSON (flexible structure supporting nested maps and numbers)';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_transaction_hash IS 'Layer 1 transaction hash';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_absolute_slot IS 'Layer 1 absolute slot number';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_creation_slot IS 'Layer 1 creation slot number';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_publish_status IS 'Layer 1 publish status';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_publish_status_error_reason IS 'Layer 1 publish status error reason';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_publish_retry IS 'Layer 1 publish retry count';
COMMENT ON COLUMN blockchain_publisher_report_v2.l1_finality_score IS 'Layer 1 finality score';

-- Create index for querying reports by organisation and status
CREATE INDEX idx_blockchain_publisher_report_v2_org_status
ON blockchain_publisher_report_v2 (organisation_id, l1_publish_status, created_at);

-- Create index for querying reports by template type and period
CREATE INDEX idx_blockchain_publisher_report_v2_template_period
ON blockchain_publisher_report_v2 (organisation_id, report_template_type, year, period);

-- Create index for querying reports by interval type and year
CREATE INDEX idx_blockchain_publisher_report_v2_interval_year
ON blockchain_publisher_report_v2 (organisation_id, interval_type, year);

