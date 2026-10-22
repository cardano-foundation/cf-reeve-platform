### Accounting-Core
- `accounting_core_reconcilation` - Reconcilation (AUDIT)
- `accounting_core_reconcilation_violation` - Reconcilation Violation (AUDIT)
- `accounting_core_transaction` - Transaction (AUDIT)
- `accounting_core_transaction_item` - Transaction Item (AUDIT)
- `accounting_core_transaction_violation` - Transaction Violation (AUDIT)
- `accounting_core_transaction_batch` - Transaction Batch (AUDIT)
- `accounting_core_transaction_batch_assoc` - Batch Association between Transaction and Batch (AUDIT)

### Blockchain Publisher
- `blockchain_publisher_transaction` - Transaction
- `blockchain_publisher_transaction_item` - Transaction Item
- `blockchain_publisher_document` - Encrypted document pending/after L1+IPFS publication
- `blockchain_publisher_document_slot` - Envelope slot (ephemeral pub + wrapped DEK)

### Netsuite Adapter
- `netsuite_adapter_ingestion` - NetSuite Adapter Ingestion
- `netsuite_adapter_code_mapping` - NetSuite Adapter Code Mapping

### Organisation
- `organisation` - Organisation (AUDIT)
- `organisation_account_event` - Organisation Account Event (AUDIT)
- `organisation_chart_of_account` - Organisation Chart of Account (AUDIT)
- `organisation_currency` - Organisation Currency (AUDIT)
- `organisation_project` - Organisation Project (AUDIT)
- `organisation_vat` - Organisation VAT (AUDIT)

### Document Vault
- `document_vault_key` - Encryption public-key / addressbook entry (one organisation per entry; notification e-mail; origin + assurance tier)
- `document_vault_wrapped_record` - Opaque wrapped-key record (multi-device sync)
- `document_vault_document` - Encrypted envelope (ciphertext + metadata + publish status)
- `document_vault_document_slot` - Per-recipient wrapped-DEK slot

### Spring Data Envers
- `revinfo` - Rev Info

### Flyway
- `flyway_schema_history` - Flyway Schema History

### Yaci Store
- `cursor_` - Cursor
- `era` - Era
- `block` - Block
- `invalid_transaction` - Invalid Transaction
- `rollback` - Rollback
- `transaction` - Transaction
- `transaction_witness` - Transaction Witness
- `withdrawal` - Withdrawal
