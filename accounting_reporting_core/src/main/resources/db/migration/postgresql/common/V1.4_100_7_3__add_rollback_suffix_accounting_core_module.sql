ALTER TABLE accounting_core_transaction
ADD COLUMN rollback_suffix varchar;

ALTER TABLE accounting_core_transaction_aud
ADD COLUMN rollback_suffix varchar;