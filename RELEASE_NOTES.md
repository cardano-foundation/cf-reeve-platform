# Reeve - Version 1.4.0 Release Notes 🚀
**Release Date:** 05.03.2026

## Overview
Version 1.4.0 focuses on expanding the platform's data accessibility and strengthening the reconciliation engine. This release introduces comprehensive download capabilities for transactions and reports, a dedicated reconciliation statistics API, and a new `CustomerCredit` transaction type. Alongside these features, significant work has gone into stabilising and refining the reconciliation workflow through targeted bug fixes and improved data validation.

## 🎯 Major Features

### Download & Export Capabilities
This release delivers a complete suite of **data download and export endpoints**, enabling users to extract and analyse financial data directly from the platform.

**Key capabilities:**
- **Transaction download endpoint**: New API endpoint to download transactions in bulk, supporting flexible filtering and pagination (#553)
- **Report download endpoint**: New API endpoint to download generated reports (#558)
- **Download improvements**: Enhanced download functionality with additional filtering parameters and improved batch selection (#579)

### Reconciliation Statistics API
A dedicated reconciliation statistics endpoint provides deeper insight into the reconciliation status of financial data.

**Key capabilities:**
- **Reconciliation statistics for date range**: New endpoint to retrieve reconciliation statistics scoped to a configurable date range, giving teams better visibility into outstanding and reconciled items (#562)
- **Event-driven reconciliation**: Added event listener for `ReconciliationCreatedEvent` to ensure downstream processes react correctly to newly created reconciliations (#578)

### New Transaction Type: CustomerCredit
The platform now supports an expanded set of transaction types to better reflect real-world financial operations.

**Key capabilities:**
- **CustomerCredit transaction type**: Added `CustomerCredit` as a supported transaction type, extending the existing set that includes `CustomerInvoice` and others (#594)

### Validation Improvements
Improved validation logic across data import workflows reduces the risk of corrupt or incomplete data entering the system.

**Key capabilities:**
- **Empty file validation for report CSV and report templates**: The system now rejects empty report CSV imports and empty report template submissions with a clear error response, preventing silent failures (#576)

### Additional Improvements
- **Module-wide conditional loading**: Optional integrations (e.g. NetSuite) can now be fully deactivated via configuration, preventing unnecessary client initialisation when the module is disabled (#564)
- **Selective transaction republishing**: Transactions are now only republished to the blockchain when a meaningful change has been detected, reducing unnecessary on-chain activity and improving system efficiency (#566)
- **ERP violation tracking**: A new violation type is created when transactions are missing in the ERP system, improving auditability and alerting for data synchronisation gaps (#570)
- **Restored LCY/FCY amounts in publish schema**: Amount fields for local currency (LCY) and foreign currency (FCY) have been restored to the publish transactions schema to ensure complete data is submitted on-chain (#584)
- **API clean-up**: Removed unused controller paths and consolidated the on-chain indexer configuration for a leaner API surface (#577)

### Infrastructure & Maintenance
- **OpenAPI version upgrade**: Updated the OpenAPI specification version to keep API documentation tooling current (#582)
- **Removed Zalando problem library**: Replaced the Zalando problem library with a simpler, in-house error response approach to reduce external dependencies (#583)
- **CI/CD**: Migrated CI jobs to GitHub-hosted runners for improved reliability (#569)

## 🐞 Bug Fixes

### Reconciliation
- **Reconciliation indexer not active**: Inactive reconciliation indexer now correctly marks the sync job as a violation instead of silently failing (#571)
- **Reconciliation filter by transaction ID**: Fixed filtering reconciliations by transaction ID to return accurate results (#580)
- **Wrong statistics for unreconciled transactions**: Corrected the statistics calculation that was returning incorrect counts for unreconciled transactions (#581)
- **Transactions missing reconciliation date**: Transactions imported from a new ERP source without a reconciliation date are now handled gracefully (#587)
- **Reconciliation CSV missing from source filter**: The `CSV` source type is now correctly included in the reconciliation source filter options (#591)
- **Reconciliation sort order in search page**: Fixed sorting by reconciliation status in the transaction search page (#592)
- **Status not returned for reimported transactions**: Fixed an issue where the reconciliation status was not correctly returned after a transaction was reimported (#593)
- **Reconciliation status and date empty**: Resolved an edge case where the reconciliation status and date fields were returned as empty (#598)
- **Reconciliation exceptions**: Fixed a series of runtime exceptions in the reconciliation processing pipeline (#599, #600)

### Other
- **Migration renamed**: Fixed a database migration naming conflict that could prevent clean schema migrations (#585)

---

**Full Comparison**: https://github.com/cardano-foundation/cf-reeve-platform/compare/1.3.1...release/1.4.0
