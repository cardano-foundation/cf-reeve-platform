UPDATE accounting_core_transaction_violation SET code = 'TRANSACTION_NOT_IN_ERP' WHERE code = 'TX_NOT_IN_ERP';
UPDATE accounting_core_transaction_violation_aud SET code = 'TRANSACTION_NOT_IN_ERP' WHERE code = 'TX_NOT_IN_ERP';
