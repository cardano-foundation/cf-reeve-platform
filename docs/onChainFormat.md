# On-Chain Metadata Format

Reeve uses a standardized on-chain metadata format identified by **label 1447**. The label was chosen in reference to 1447, the death year of [Luca Pacioli](https://en.wikipedia.org/wiki/Luca_Pacioli), the renowned Italian mathematician widely regarded as the father of accounting and bookkeeping.

## Purpose and Design Philosophy

This metadata format enables organizations to anchor both individual financial transactions and custom organizational reports on the Cardano blockchain as an immutable and publicly verifiable layer of trust. The format is designed with the following principles:

- **Human Readability**: Anyone can view the raw data on a block explorer and understand it immediately, without needing specialized software
- **Machine Interpretability**: The structured format allows for automated processing and verification
- **Flexibility**: Organizations can customize reports according to their specific needs, purposes, and structures
- **Transparency**: Financial records remain transparent and independently verifiable, even if the application used to publish them becomes unavailable
- **Extensibility**: The structure can accommodate future enhancements without breaking existing implementations

**Trade-off**: The format prioritizes readability over compactness. While this results in larger transaction sizes, it ensures universal accessibility and long-term trust by making financial records immediately understandable to auditors, stakeholders, and the public.

## General Structure

All metadata entries under label 1447 follow this base structure, containing organization information, metadata versioning, and the type of financial records being published:

```json
{
  "1447": {
    "org": {
      "id": "string",           // SHA3-256 hash of <CountryCode>::<TaxIdNumber>
      "name": "string",          // Legal name of the organization
      "currency_id": "string",   // Functional currency (ISO 4217 code)
      "country_code": "string",  // ISO 3166-1 alpha-2 country code
      "tax_id_number": "string"  // Tax identification number
    },
    "metadata": {
      "creation_slot": "u64",                      // Cardano slot number when created
      "timestamp": "string",                       // ISO-8601 timestamp
      "version": "string"                          // Metadata format version (e.g., "1.1")
    },
    "type": "string",            // Type of metadata: "INDIVIDUAL_TRANSACTIONS" or "REPORT"
    "data": {}                   // Type-specific data structure
  }
}
```

### Organization Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique identifier, SHA3-256 hash of `<CountryCode>::<TaxIdNumber>` |
| `name` | string | Legal name of the organization |
| `currency_id` | string | Functional currency using ISO 4217 code (e.g., "ISO_4217:USD") |
| `country_code` | string | ISO 3166-1 alpha-2 country code (e.g., "CH", "US") |
| `tax_id_number` | string | Tax identification number specific to the organization's jurisdiction |

### Metadata Types

Reeve currently supports two metadata types:

- **`INDIVIDUAL_TRANSACTIONS`**: Individual accounting transactions of the organization
- **`REPORT`**: Custom financial reports (balance sheets, income statements, etc.)

## Type: Individual Transactions

The `INDIVIDUAL_TRANSACTIONS` type stores individual accounting transactions within the `data` array. This allows organizations to anchor their financial transactions on-chain for transparency and audit purposes.

### Transaction Fields

Each transaction in the `data` array has the following required fields:

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique identifier, SHA3-256 hash of `<OrgId>::<TxNumber>` |
| `number` | string | Transaction identifier from the accounting system |
| `batch_id` | string | Identifier for the batch this transaction belongs to |
| `type` | enum | Type of transaction (see transaction types below) |
| `date` | string | Transaction date in ISO 8601 format (YYYY-MM-DD) |
| `accounting_period` | string | Accounting period (e.g., "2025-01" for January 2025) |
| `items` | array | Array of transaction items (individual entries) |

### Transaction Types

The following transaction types are defined and can be extended as needed:

| Type | Description |
|------|-------------|
| `Journal` | General journal entry for recording various financial transactions |
| `VendorBill` | Bill from a vendor, representing an amount owed to a supplier |
| `VendorPayment` | Payment made to a vendor, reducing the amount owed |
| `CustomerInvoice` | Invoice issued to a customer, representing an amount owed by them |
| `CustomerPayment` | Payment received from a customer, reducing the amount owed by them |
| `BillCredit` | Credit note issued against a vendor bill |
| `CardCharge` | Charge made to a credit card, representing an expense |
| `CardRefund` | Refund issued to a credit card |
| `FxRevaluation` | Foreign exchange revaluation to adjust foreign currency values |
| `Transfer` | Transfer of funds between accounts |
| `ExpenseReport` | Employee expense report summarizing costs and reimbursements |

### Transaction Items

Each transaction contains one or more items representing individual financial entries. Items have the following fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique identifier, SHA3-256 hash of `<TransactionId>::<LineNo>` |
| `amount` | string | Yes | Monetary amount in the item's currency |
| `fx_rate` | string | Yes | Foreign exchange rate converting to functional currency |
| `document` | object | Yes | Document details (number, currency) |
| `event` | object | No | Event details with code and name |
| `project` | object | No | Project details with custom code and name |
| `cost_center` | object | No | Cost center details with custom code and name |

#### Document Object

The `document` object contains details about the source document:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `number` | string | Yes | Document reference number |
| `currency` | object | Yes | Currency information with `id` (ISO format) and `cust_code` |
| `vat` | object | No | VAT details with `cust_code` and `rate` |
| `counterparty` | object | No | Counterparty details with `cust_code` and `type` |

### Example: Individual Transactions

```json
{
  "1447": {
    "org": {
      "id": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
      "name": "Cardano Foundation",
      "currency_id": "ISO_4217:CHF",
      "country_code": "CH",
      "tax_id_number": "CHE-184477354"
    },
    "metadata": {
      "creation_slot": 1,
      "timestamp": "2025-10-08T04:20:58.126884408Z",
      "version": "1.1"
    },
    "type": "INDIVIDUAL_TRANSACTIONS",
    "data": [
      {
        "id": "dc69c1c0c25f292dfffe924df7647cb5e08295b26b74802a2a7360cac79c716d",
        "number": "JOURNAL8238",
        "batch_id": "1287f06de62dcd6f4ff6e1834088df4e20c21bc83881cb4b765d51de8f42737b",
        "type": "Journal",
        "date": "2025-04-07",
        "accounting_period": "2025-04",
        "items": [
          {
            "id": "5c550d9f9d8b5c1890ff062f0501401c89bd29062a9b42fa3579385f0c7a1729",
            "amount": "30760.41",
            "fx_rate": "0.10388169",
            "document": {
              "number": "JE-8238",
              "currency": {
                "id": "ISO_24165:ADA:HWGL1C2CK",
                "cust_code": "ADA"
              }
            },
            "event": {
              "code": "1310T000",
              "name": "Crypto inflow - Crypto/Transfer acc"
            }
          },
          {
            "id": "f4aecc16caddc74327cb22de8655fdf4d717896ca5390961eae6d19364c80687",
            "amount": "30760.41",
            "fx_rate": "0.10388169",
            "document": {
              "number": "JE-8238",
              "currency": {
                "id": "ISO_24165:ADA:HWGL1C2CK",
                "cust_code": "ADA"
              }
            },
            "event": {
              "code": "T0001310",
              "name": "Crypto outflow - Transfer acc/Crypto"
            }
          }
        ]
      }
    ]
  }
}
```

## Type: Report

The type `REPORT` is used to publish reports of organisation. These reports can be highly customized resulting in the need for a flexible and adjustable structure. 

Required fields:

| Field      | Type                        | Description                                                                                                                                                            |
| ---------- | --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `interval` | string                      | The reporting interval, e.g., "YEARLY", "QUARTERLY", "MONTHLY", etc.                                                                                                   |
| `year`     | string                      | The year of the report, e.g., "2025".                                                                                                                                  |
| `period`   | integer                     | The period of the report according to the interval, e.g., if Monthly 1 for January, 2 for February, if quarterly 1 for Q1, 2 for Q2                                    |
| `subtype`  | string                      | Definition of what of the report type - Organisations can have custom reports, e.g., "BALANCE_SHEET", "INCOME_STATEMENT"                                               |
| `data`     | anyOf [string, data object] | The actual report data, which can be highly customized and structured according to the organisation's needs. This object should repesent categories and subcategories. |

#### Example json:

```json
{
  <General structure>,
  "type": "REPORT",
  "interval": "MONTHLY",
  "year": "2025",
  "period": 12,
  "subtype": "BALANCE_SHEET",
  "data": {
    "assets": {
      "current_assets": {
        "cash": "1000"
      },
      "non_current_assets": {
        "property": "5000"
      }
    },
    "liabilities": {
      "current_liabilities": {
        "accounts_payable": "200"
      },
      "non_current_liabilities": {
        "long_term_debt": "1000"
      }
    }
  }
}
```

## Glossary

This section defines key terms used throughout the on-chain metadata format.

### Cost Center

A cost center is an organizational unit used for internal accounting and cost allocation. It helps organizations track expenses by department, project, or functional area. Cost centers are identified by a code and name combination.

**Example**: `"7777777:Foundation"` where `7777777` is the cost center code and `Foundation` is the cost center name.

### Counterparty

A counterparty is another entity involved in a transaction or business relationship. The counterparty field identifies the organization or individual on the other side of a transaction using their hashed organization ID or name.

**Example**: `"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"` (hashed organization identifier)

### FX Rate

The foreign exchange (FX) rate is the conversion rate between two currencies at the time of a transaction. It is used when transactions involve multiple currencies to provide transparency about the exchange rate applied.

**Format**: `"<from_currency>:<to_currency>=<rate>"`

**Example**: `"ISO_4217:EUR:ISO_4217:CHF=0.9345"` indicates 1 EUR equals 0.9345 CHF.

### VAT (Value Added Tax)

VAT is a consumption tax applied to goods and services. The VAT code identifies the tax category and rate applicable to a transaction. Organizations must track VAT for tax compliance and reporting.

**Format**: `"<code>:<description>:<rate>"`

**Example**: `"8.1:Input tax 8.1% (381):8.1"` where:
- `8.1` is the VAT code
- `Input tax 8.1% (381)` is the description
- `8.1` is the percentage rate

### Document

A document is any supporting file or reference associated with a transaction. Documents provide proof and context for financial transactions. The document object includes:

- `type`: The kind of document (e.g., "INVOICE", "RECEIPT", "CONTRACT")
- `number`: The document identifier or reference number
- `date`: The document date in ISO 8601 format

**Example**:
```json
{
  "type": "INVOICE",
  "number": "INV-2025-001",
  "date": "2025-01-15"
}
```

### Versioning

The `ver` field tracks different versions of reports or data. As organizations update their financial reports or correct errors, they increment the version number. This allows users to track changes over time and access historical versions.

**Example**: A balance sheet for June 2025 might have versions 1, 2, and 3, where version 3 is the most current.

### Interval Types

Organizations can report financial data at different time intervals:

- **YEAR**: Annual reporting covering a full fiscal year
- **QUARTER**: Quarterly reporting (Q1-Q4, represented as periods 1-4)
- **MONTH**: Monthly reporting (January-December, represented as periods 1-12)

The combination of `interval`, `year`, and `period` uniquely identifies a reporting timeframe.
