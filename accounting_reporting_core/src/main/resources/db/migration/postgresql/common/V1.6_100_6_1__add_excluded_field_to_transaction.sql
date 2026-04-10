ALTER TABLE accounting_core_transaction
    ADD COLUMN IF NOT EXISTS excluded_report BOOLEAN;

ALTER TABLE accounting_core_transaction_aud
    ADD COLUMN IF NOT EXISTS excluded_report BOOLEAN;
