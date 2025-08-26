ALTER TYPE accounting_core_ledger_dispatch_status_type ADD VALUE 'RETRYING';
ALTER TYPE accounting_core_ledger_dispatch_status_type ADD VALUE 'FAILED';


ALTER TABLE accounting_core_transaction
ADD COLUMN ledger_dispatch_status_error_reason TEXT;

ALTER TABLE accounting_core_transaction_aud
ADD COLUMN ledger_dispatch_status_error_reason TEXT;

ALTER TABLE accounting_core_report
ADD COLUMN ledger_dispatch_status_error_reason TEXT;

ALTER TABLE accounting_core_report_aud
ADD COLUMN ledger_dispatch_status_error_reason TEXT;