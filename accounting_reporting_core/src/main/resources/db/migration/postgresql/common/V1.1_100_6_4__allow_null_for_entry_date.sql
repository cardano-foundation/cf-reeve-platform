ALTER TABLE accounting_core_transaction
ALTER COLUMN entry_date DROP NOT NULL;

ALTER TABLE accounting_core_transaction
ALTER COLUMN accounting_period DROP NOT NULL;

ALTER TABLE accounting_core_transaction_aud
ALTER COLUMN entry_date DROP NOT NULL;

ALTER TABLE accounting_core_transaction_aud
ALTER COLUMN accounting_period DROP NOT NULL;