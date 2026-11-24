-- LOB-2492: rename the published-report snapshot column to match the reporting module.
-- Only the column changes; the on-chain metadata key stays "standard" (API3 metadata 1.3).

ALTER TABLE blockchain_publisher_report_v2 RENAME COLUMN accounting_regime TO report_standard;
