# CSV Importer
Reeve offers the possibility to add data via CSV Files. The idea is to make it as easy as possible for users to start using Reeve.
The implementation of a custom ERP adapter could take some time and thus the CSV Importer is a good way to get started.

It is possible to import the following data:
- Transactions


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