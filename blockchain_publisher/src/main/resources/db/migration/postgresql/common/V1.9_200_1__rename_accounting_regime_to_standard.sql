-- LOB-2492: rename published-report "accounting_regime" column to "standard".
-- On-chain metadata key already emitted as "standard" since API3 metadata v1.3.
ALTER TABLE blockchain_publisher_report_v2 RENAME COLUMN accounting_regime TO standard;
