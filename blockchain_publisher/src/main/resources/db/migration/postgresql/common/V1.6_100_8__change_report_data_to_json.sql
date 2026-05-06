-- Change report_data from JSONB to JSON to preserve key insertion order.
-- JSONB reorders keys internally; JSON preserves the original text order,
-- which is required so the field sequence defined in the report template
-- is maintained when the publisher serialises metadata to the blockchain.
ALTER TABLE blockchain_publisher_report_v2
    ALTER COLUMN report_data TYPE JSON USING report_data::TEXT::JSON;
