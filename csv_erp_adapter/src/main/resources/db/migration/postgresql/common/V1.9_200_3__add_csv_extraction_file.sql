-- An uploaded CSV must survive between the event that receives it and the event that processes it,
-- and those can be consumed by different replicas. Rows are short-lived: deleted when processing
-- starts, or by a cleanup job if it never does.
CREATE TABLE csv_erp_adapter_extraction_file (
    id VARCHAR(255) PRIMARY KEY,
    organisation_id VARCHAR(255) NOT NULL,
    file BYTEA,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_csv_erp_adapter_extraction_file_created_at
    ON csv_erp_adapter_extraction_file (created_at);
