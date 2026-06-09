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
    "type": "string",            // Type of metadata: "INDIVIDUAL_TRANSACTIONS", "REPORT", or "EVENT_BUNDLE"
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

Reeve currently supports the following metadata types:

- **`INDIVIDUAL_TRANSACTIONS`**: Individual accounting transactions of the organization
- **`REPORT`**: Custom financial reports (balance sheets, income statements, etc.)
- **`EVENT_BUNDLE`** *(since v1.2)*: Lifecycle events of grant or treasury funding (allocation, spending, refund)

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

## Type: Event Bundle

The `EVENT_BUNDLE` type anchors the lifecycle of grant or treasury funding. Each bundle is a self-contained record of **one** lifecycle event — `ALLOCATION`, `SPENDING`, or `REFUND` — scoped to a single funding source, activity, and milestone. Because every bundle re-states its funding context (the `allocation` block), the complete history of a grant can be reconstructed and aggregated directly from the chain without a separate registration step, mirroring the self-containment of the `org` heather.

`EVENT_BUNDLE` deliberately reuses the conventions of the other types: SHA3-256 `<Parent>::<Child>` id derivation, `ISO_4217` / `ISO_24165` currency identifiers, the `"<from>:<to>=<rate>"` FX-rate string, amounts as strings, and the `document` object.

For `EVENT_BUNDLE`, `data` is a **single object** (not an array).

### Bundle Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique identifier, SHA3-256 hash of `<OrgId>::<funding_id>::<type>::<sequence>` |
| `type` | enum | Yes | Event type: `ALLOCATION`, `SPENDING`, or `REFUND` |
| `date` | string | Yes | Date the event occurred, ISO 8601 (YYYY-MM-DD). Distinct from the `metadata.timestamp` submission time. |
| `accounting_period` | string | Yes | Accounting period (e.g., "2026-02"), as in `INDIVIDUAL_TRANSACTIONS` |
| `allocation` | object | Yes | Funding context. Present in all three event types; it is the join key for aggregation. |
| `milestone` | object | Conditional | Required for `ALLOCATION` and `REFUND`. Milestone the disbursement/refund relates to. |
| `amount` | string | Conditional | Required for `ALLOCATION` and `REFUND`. Total amount moved by the event, in `currency`. |
| `currency` | object | Conditional | Required for `ALLOCATION` and `REFUND`. Currency of `amount`. |
| `items` | array | Conditional | Required for `SPENDING`. Batch of spend line items, all scoped to `allocation.milestone_id`. |

### Allocation Object

The `allocation` object identifies the funding source and activity. It appears in every bundle.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `funding_id` | string | Yes | Identifier of the funding source. Stable across every record related to this funding — the primary join key. |
| `activity_id` | string | Yes | Identifier of the funded activity. |
| `activity_title` | string | Conditional | Human-readable activity name. Required for `ALLOCATION`/`REFUND`, optional for `SPENDING`. |
| `milestone_id` | string | Conditional | Required for `SPENDING`: the milestone the spend batch is scoped to. For `ALLOCATION`/`REFUND` the milestone is carried in the `milestone` object instead. |
| `round_id` | string \| int | No | Funding round or tranche number. Links multiple rounds of funding to the same activity. |
| `funding_tx` | string | Conditional | On-chain disbursement transaction hash, verifiable directly in the ledger. Exactly one of `funding_tx` / `funding_doc_hash` is required on `ALLOCATION` and `REFUND`; optional context on `SPENDING`. |
| `funding_doc_hash` | string | Conditional | SHA-256 hash or IPFS CID of off-chain payment evidence. Required when `funding_tx` is absent on `ALLOCATION`/`REFUND`. |

### Milestone Object

Used by `ALLOCATION` and `REFUND`. If a single disbursement covers several milestones, `milestone` MAY be an array of these objects; readers must accept both forms.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `milestone_id` | string | Yes | Identifier of the milestone. |
| `label` | string | Yes | Human-readable milestone name. |
| `amount` | string | Yes | Amount allocated to this milestone. |
| `currency` | object | Yes | Currency of the milestone allocation, with `id` (ISO format) and `cust_code`. |
| `date` | string | Yes | Milestone due date, ISO 8601. |

### Spend Item Fields

Each item in the `items` array (for `SPENDING`) represents one spend, scoped to `allocation.milestone_id`. Items map onto the same conventions as transaction items.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique identifier, SHA3-256 hash of `<BundleId>::<LineNo>`. |
| `category` | string | Yes | Spending category, inherited from the approval-proposal budget lines (not defined by Reeve). |
| `vendor` | string | Yes | Vendor, contractor, or payment recipient. May be a hashed organization id (counterparty convention). |
| `amount` | string | Yes | Amount in the currency the payment was actually made in. |
| `currency` | object | Yes | Payment currency, with `id` (ISO format) and `cust_code`. |
| `fx_rate` | string | Yes | FX rate converting `amount` to the reporting currency (`org.currency_id`), format `"<from>:<to>=<rate>"`. |
| `amount_rcy` | string | No | Amount in the reporting currency. Derivable from `amount` × rate; included for readability and must reconcile. |
| `date` | string | Yes | Actual date the spend occurred, ISO 8601. |
| `document` | object | No | Supporting evidence (see Document object): `type`, `number`, `date`, and a `hash` (SHA-256 / IPFS CID). |
| `notes` | string | No | Free-text description, or a link to an internal accounting reference. |

### Event Type Field Matrix

| Field (`data.*`) | ALLOCATION | SPENDING | REFUND |
|------------------|:----------:|:--------:|:------:|
| `id`, `type`, `date`, `accounting_period` | Yes | Yes | Yes |
| `allocation.funding_id`, `allocation.activity_id` | Yes | Yes | Yes |
| `allocation.activity_title` | Yes | Optional | Yes |
| `allocation.milestone_id` | — (in `milestone`) | Yes | — (in `milestone`) |
| `allocation.round_id` | Optional | — | Optional |
| `allocation.funding_tx` / `funding_doc_hash` | Yes (one-of) | Optional | Yes (one-of) |
| `milestone` | Yes | — | Yes |
| `amount`, `currency` | Yes | — | Yes |
| `items` | — | Yes | — |

`REFUND` is structurally identical to `ALLOCATION`; only `type` differs.

### Example: Event Bundle — Allocation

```json
{
  "1447": {
    "org": {
      "id": "d9346a676f48818e7ff5e0767dbfa445970f1c0b45d37035324443eea7d12b6d",
      "name": "Reef eG",
      "currency_id": "ISO_4217:EUR",
      "country_code": "DE",
      "tax_id_number": "GnR 1234 Braunschweig"
    },
    "metadata": {
      "creation_slot": 123456789,
      "timestamp": "2026-02-17T23:59:00Z",
      "version": "1.2"
    },
    "type": "EVENT_BUNDLE",
    "data": {
      "id": "9c06037cb03b31040afc3946f3f48c14327b10eed7724769e750fbcefe5326bf",
      "type": "ALLOCATION",
      "date": "2026-02-17",
      "accounting_period": "2026-02",
      "allocation": {
        "funding_id": "GRANT-2026-REEF-014",
        "activity_id": "ACT-REEF-RESTORATION",
        "activity_title": "Coastal reef restoration programme",
        "round_id": 1,
        "funding_tx": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
      },
      "amount": "50000.00",
      "currency": { "id": "ISO_4217:EUR", "cust_code": "EUR" },
      "milestone": {
        "milestone_id": "M1",
        "label": "Site survey & permitting",
        "amount": "50000.00",
        "currency": { "id": "ISO_4217:EUR", "cust_code": "EUR" },
        "date": "2026-06-30"
      }
    }
  }
}
```

### Example: Event Bundle — Spending

```json
{
  "1447": {
    "org": {
      "id": "d9346a676f48818e7ff5e0767dbfa445970f1c0b45d37035324443eea7d12b6d",
      "name": "Reef eG",
      "currency_id": "ISO_4217:EUR",
      "country_code": "DE",
      "tax_id_number": "GnR 1234 Braunschweig"
    },
    "metadata": {
      "creation_slot": 124000000,
      "timestamp": "2026-03-31T23:59:00Z",
      "version": "1.2"
    },
    "type": "EVENT_BUNDLE",
    "data": {
      "id": "48b2ebcc69af396c9ed2ae2f6ea56b12d871653a689837103b4f074e971465b2",
      "type": "SPENDING",
      "date": "2026-03-31",
      "accounting_period": "2026-03",
      "allocation": {
        "funding_id": "GRANT-2026-REEF-014",
        "activity_id": "ACT-REEF-RESTORATION",
        "milestone_id": "M1",
        "funding_tx": "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
      },
      "items": [
        {
          "id": "27a257c1a6e1106d3e2a3955f79954b670091fc28c224c0acc38eaacce5642a8",
          "category": "Surveying services",
          "vendor": "Nordsee Marine Survey GmbH",
          "amount": "12000.00",
          "currency": { "id": "ISO_4217:EUR", "cust_code": "EUR" },
          "fx_rate": "ISO_4217:EUR:ISO_4217:EUR=1.0000",
          "amount_rcy": "12000.00",
          "date": "2026-03-12",
          "document": {
            "type": "INVOICE",
            "number": "NMS-2026-0042",
            "date": "2026-03-12",
            "hash": "bafybeid7m2x...ipfs-cid"
          },
          "notes": "Bathymetric survey, milestone M1"
        },
        {
          "id": "bf7a718761ada1db74c98bba17a2cc4a621a904e0626aa1dbef076d6fc510367",
          "category": "Equipment",
          "vendor": "Reef Substrate Co.",
          "amount": "5000.00",
          "currency": { "id": "ISO_4217:USD", "cust_code": "USD" },
          "fx_rate": "ISO_4217:USD:ISO_4217:EUR=0.9200",
          "amount_rcy": "4600.00",
          "date": "2026-03-20",
          "document": {
            "type": "RECEIPT",
            "number": "RSC-88231",
            "date": "2026-03-20",
            "hash": "9f2c4a...sha256"
          }
        }
      ]
    }
  }
}
```

### Example: Event Bundle — Refund

```json
{
  "1447": {
    "org": {
      "id": "d9346a676f48818e7ff5e0767dbfa445970f1c0b45d37035324443eea7d12b6d",
      "name": "Reef eG",
      "currency_id": "ISO_4217:EUR",
      "country_code": "DE",
      "tax_id_number": "GnR 1234 Braunschweig"
    },
    "metadata": {
      "creation_slot": 124500000,
      "timestamp": "2026-07-05T10:00:00Z",
      "version": "1.2"
    },
    "type": "EVENT_BUNDLE",
    "data": {
      "id": "a535d0d73fac92fd7b3bc8c2004e39755b63a5ef2a8fc462b89aef694bf5a275",
      "type": "REFUND",
      "date": "2026-07-05",
      "accounting_period": "2026-07",
      "allocation": {
        "funding_id": "GRANT-2026-REEF-014",
        "activity_id": "ACT-REEF-RESTORATION",
        "activity_title": "Coastal reef restoration programme",
        "round_id": 1,
        "funding_tx": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f"
      },
      "amount": "3200.00",
      "currency": { "id": "ISO_4217:EUR", "cust_code": "EUR" },
      "milestone": {
        "milestone_id": "M1",
        "label": "Site survey & permitting",
        "amount": "3200.00",
        "currency": { "id": "ISO_4217:EUR", "cust_code": "EUR" },
        "date": "2026-06-30"
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

### Funding (Allocation)

Funding, recorded by an `ALLOCATION` event bundle, is the disbursement of resources from a treasury or grantor to a funded organization. It establishes the `funding_id`, activity, and milestone reference that all subsequent `SPENDING` and `REFUND` records point back to. A disbursement may be on-chain (`funding_tx`) or off-chain (`funding_doc_hash`).

### Milestone

A milestone is a defined checkpoint of a funded activity against which funds are allocated and spending is scoped. Identified by `milestone_id`, it carries a human-readable `label`, an allocated `amount` and `currency`, and a due `date`. Spending records reference the milestone via `allocation.milestone_id`.

### Event Bundle

An event bundle is a single on-chain record capturing one lifecycle event of a funding relationship — `ALLOCATION`, `SPENDING`, or `REFUND`. Each bundle re-states its funding context so that a grant's full history can be aggregated from the chain. Bundle identifiers are SHA3-256 hashes of `<OrgId>::<funding_id>::<type>::<sequence>`.
