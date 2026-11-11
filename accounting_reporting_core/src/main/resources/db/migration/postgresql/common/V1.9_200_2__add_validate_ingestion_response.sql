-- Validation responses are consumed by any API replica but awaited by the one serving the request,
-- so they are shared through the database. Rows are short-lived: deleted on read, or by a cleanup job.
CREATE TABLE accounting_core_validate_ingestion_response (
    correlation_id VARCHAR(255) PRIMARY KEY,
    valid BOOLEAN NOT NULL,
    errors JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_accounting_core_validate_ingestion_response_created_at
    ON accounting_core_validate_ingestion_response (created_at);
