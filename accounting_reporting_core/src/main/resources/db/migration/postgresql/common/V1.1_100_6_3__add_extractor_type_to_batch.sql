ALTER TABLE accounting_core_transaction_batch ADD COLUMN extractor_type VARCHAR(255) NOT NULL DEFAULT 'NETSUITE';

ALTER TABLE accounting_core_transaction_batch_aud ADD COLUMN extractor_type VARCHAR(255) NOT NULL DEFAULT 'NETSUITE';