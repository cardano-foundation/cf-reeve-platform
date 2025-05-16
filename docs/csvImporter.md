# CSV Importer
Reeve offers the possibility to add data via CSV Files. The idea is to make it as easy as possible for users to start using Reeve.
The implementation of a custom ERP adapter could take some time and thus the CSV Importer is a good way to get started.

It is possible to import the following data:
- Transactions
- Account Events
- Chart of Accounts
- Ref Codes
- Report Type Field mapping
- Cost Center


## Data structure
Currently, a fixed data structure is needed to import the data.
The delimiter can be adjusted by using the environment variable `LOB_CSV_DELIMITER`.

### Transactions data structure
An example can be found here: [Transactions CSV Example](./examples/transactions_csv_example.csv)
The following columns are needed:
- `Transaction Number`: Internal transaction number (e.g. `EXPENSE_1`)
- `Transaction Date`: Date of the transaction (e.g. `25/01/2024`)
- `Transaction Type`: Type of the transaction (e.g. `INVOICE`, `PAYMENT`, `CREDIT_NOTE`, `DEBIT_NOTE`)
- `Fx Rate`: Exchange rate of the transaction (e.g. `1.2`)
- `Amount LCY Debit`: Amount in local currency (debit) (e.g. `100`)
- `Amount LCY Credit`: Amount in local currency (credit) (e.g. `200`)
- `Amount FCY Debit`: Amount in foreign currency (debit) (e.g. `100`)
- `Amount FCY Credit`: Amount in foreign currency (credit) (e.g. `200`)
- `Debit Code`: Account code of debit participant (e.g. `1000`)
- `Debit Name`: Account name of debit participant (e.g. `Cash`)
- `Credit Code`: Account code of credit participant (e.g. `2000`)
- `Credit Name`: Account name of credit participant (e.g. `Bank`)
- `Project Code`: Project code of the transaction (e.g. `PROJECT_1`)
- `Document Name`: Name of the document (e.g. `Invoice 1`)
- `Currency`: Currency of the transaction (e.g. `USD`)
- `VAT Rate`: VAT rate of the transaction (e.g. `0.2`)
- `VAT Amount`: VAT amount of the transaction (e.g. `VAT-123`)

### Account Events data structure
An example can be found here: [Account Events CSV Example](./examples/account_event_csv_example.csv)
The following columns are needed:
- `Debit Reference Code`: Reference code of the debit account (e.g. `1000`)
- `Credit Reference Code`: Reference code of the credit account (e.g. `2000`)
- `Name`: Name of the account event (e.g. `Invoice 1`)
- `Active`: Active status of the account event (e.g. `true`)

### Chart of Accounts data structure
An example can be found here: [Chart of Accounts CSV Example](./examples/chart_of_account_csv_example.csv)
The following columns are needed:
- `Customer Code`: Customer code of the account (e.g. `1000`)
- `Event Reference Code`: Reference code of the account (e.g. `2000`)
- `Reference Code`: Reference code of the account (e.g. `2000`)
- `Name`: Name of the account (e.g. `Cash`)
- `Type`: Type of the account (e.g. `ASSET`)
- `Sub Type`: Sub type of the account (e.g. `BANK`)
- `Currency`: Currency of the account (e.g. `USD`)
- `CounterParty`: Counterparty of the account (e.g. `Vendor1`)
- `Parent Customer Code`: Parent customer code of the account (e.g. `1001`)
- `Active`: Active status of the account (e.g. `true`)
- `Open Balance FCY`: Open Balance of the account in foreign currency (e.g. `100`)
- `Open Balance LCY`: Open Balance of the account in local currency (e.g. `200`)
- `Open Balance Currency ID FCY`: Original currency ID of the account in foreign currency (e.g. `USD`)
- `Open Balance Currency ID LCY`: Original currency ID of the account in local currency (e.g. `USD`)
- `Open Balance Type`: Type of the open balance (e.g. `DEBIT`)
- `Open Balance Date`: Date of the open balance (e.g. `25/01/2024`)

### Ref Codes data structure
An example can be found here: [Ref Codes CSV Example](./examples/ref_code_csv_example.csv)
The following columns are needed:
- `Reference Code`: Reference code of the ref code (e.g. `1000`)
- `Name`: Name of the ref code (e.g. `Cash`)
- `Parent Reference Code`: Parent reference code of the ref code (e.g. `1001`)
- `Active`: Active status of the ref code (e.g. `true`)

### Report Type Field mapping data structure
An example can be found here: [Report Type Field mapping CSV Example](./examples/report_type_field_csv_example.csv)
The following columns are needed:
- `Report Type`: Type of the report (e.g. `INCOME_STATEMENT`)
- `Report Type Field`: Name of the field (e.g. `REVENUE`)
- `Sub Type`: Type of the field (e.g. `CASH_AND_CASH_EQUIVALENTS`)

### Cost Center data structure
An example can be found here: [Cost Center CSV Example](./examples/cost_center_csv_example.csv)
The following columns are needed:
- `Customer code`: Code of the cost center (e.g. `COST_CENTER_1`)
- `External customer code`: Name of the external customer code (e.g. `Cost Center 1`)
- `Name`: Name of the cost center (e.g. `Cost Center 1`)
- `Parent customer code`: Parent code of the cost center (e.g. `COST_CENTER_2`)

### Project data structure
An example can be found here: [Project CSV Example](./examples/project_csv_example.csv)
The following columns are needed:
- `Customer code`: Code of the project (e.g. `PROJECT_1`)
- `External customer code`: Name of the external project code (e.g. `Project 1`)
- `Name`: Name of the project (e.g. `Project 1`)
- `Parent customer code`: Parent code of the project (e.g. `PROJECT_2`)