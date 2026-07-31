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
      "creation_slot": "u64",                      // Cardano slot number when created (NOT on DOCUMENT)
      "timestamp": "string",                       // ISO-8601 timestamp (NOT on DOCUMENT)
      "version": "string"                          // Metadata format version (e.g., "1.1")
    },
    "type": "string",            // Type of metadata: "INDIVIDUAL_TRANSACTIONS", "REPORT" or "FUNDING"
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

Reeve currently supports three metadata types:

- **`INDIVIDUAL_TRANSACTIONS`**: Individual accounting transactions of the organization
- **`REPORT`**: Custom financial reports (balance sheets, income statements, etc.)
- **`FUNDING`**: Grant-lifecycle events (funding, spending, refunds) and organization-defined custom events

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

## Type: Funding

The `FUNDING` type anchors **grant-lifecycle events** — the funding, spending, and refund activity tied to a grant or project — as well as organization-defined **custom events**. It is used by the funding module to make the flow of grant money publicly verifiable on-chain.

A record groups one or more events belonging to a single organization. The `data` field supports two storage modes:

- **Inline**: `data` is an array of event objects, embedded directly in the transaction metadata.
- **IPFS-anchored**: `data` is a manifest object that references an off-chain document (stored on IPFS) via its CID. Used when the bundle is too large to fit comfortably in transaction metadata.

```json
{
  <General structure>,
  "type": "FUNDING",
  "data": [ /* array of event objects (inline) */ ]
}
```

> **Note on validation**: Several rules cannot be expressed in JSON Schema and are enforced by a programmatic validator instead: `org_id` matching the on-chain `org.id`, `event_count` matching the number of events, the manifest `id` derivation, and the IPFS CID matching the document bytes.

### Event Fields

Grant-lifecycle events (`FUNDING`, `SPENDING`, `REFUND`) carry:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique identifier of the event |
| `type` | string | Yes | Event type — `FUNDING`, `SPENDING`, or `REFUND` |
| `funding_tx` | string | No | Reference to the funding transaction |
| `funding_id` | string | Yes | Identifier of the funding source |
| `funding_entity` | string | Conditional | Name of the entity providing the funding; **required** for `FUNDING` events |
| `date` | string | No | Event date in ISO 8601 format (YYYY-MM-DD); applies to **all** event types. For a `SPENDING` event this is the spend date. |
| `amount_rcy` | string | Conditional | Spent amount in the organization's reporting currency; `SPENDING` only (see spend detail below) |
| `amount_fcy` | string | Conditional | Spent amount in the spend (foreign) currency; `SPENDING` only |
| `vendor` | string | No | Vendor/payee; `SPENDING` only |
| `spending_category` | string | No | Expense category; `SPENDING` only |
| `fx_rate` | string | Conditional | FX rate such that `amount_fcy = amount_rcy * fx_rate`; `SPENDING` only (see [FX Rate](#fx-rate)) |
| `hash` | string | No | Hash of the supporting document; `SPENDING` only |
| `notes` | string | No | Free-text notes; `SPENDING` only |
| `currency` | object | No | Spend currency with `id` (ISO format) and `cust_code`; `SPENDING` only |
| `allocation` | array | Yes | One entry per project this event targets (see below) |

`date` is the event date and is optional for every event type. The spend fields (`amount_rcy`, `amount_fcy`, `vendor`, `spending_category`, `fx_rate`, `hash`, `notes`, `currency`) form the event's **single spend record** and are present for `SPENDING` events only. A `SPENDING` event's spend is fully allocated: its milestone `allocated_amount`s sum to exactly `amount_rcy`.

Custom (organization-defined) events carry only the common fields:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique identifier of the event |
| `type` | string | Yes | A custom organization-defined value (not `FUNDING`/`SPENDING`/`REFUND`) |
| `date` | string | Yes | Event date in ISO 8601 format (YYYY-MM-DD) |

### Event Types

| Type | Description |
|------|-------------|
| `FUNDING` | Funds allocated to a grant, split across one or more projects and their milestones |
| `SPENDING` | Expenditure against a grant, carrying the spend record at the event level |
| `REFUND` | Funds returned/reversed for a grant |
| *custom* | Any organization-defined type (not one of the reserved values above); only the common fields are mandated and the body is free-form |

### Allocation Array

`allocation` is an array with one entry per project the event targets. `project_id`/`project_title` always identify the **root** project as is. Each entry takes exactly one of two shapes, so it is unambiguous where the money is booked:

- **Direct allocation** — the event targets the project itself: the entry carries `milestones` at the project level.
- **Sub-project allocation** — the event targets a sub-project: the entry carries a `sub_project` object holding the sub-project's own id, title and milestones (and no project-level `milestones`).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `project_id` | string | Yes | User-defined id of the root project |
| `project_title` | string | Yes | Title of the root project |
| `milestones` | array | Conditional | Milestones of a direct allocation; mutually exclusive with `sub_project` |
| `sub_project` | object | Conditional | The allocated sub-project (see below); mutually exclusive with `milestones` |

### Sub-Project Object

Present only when the allocation targets a sub-project; carries the sub-project's own identity and milestones:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sub_project_id` | string | Yes | User-defined id of the sub-project |
| `sub_project_title` | string | No | Title of the sub-project |
| `milestones` | array | Yes | Milestones of the sub-project targeted by the event |

### Milestone Object

Each entry of a `milestones` array references a milestone and the amount the event books against it:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `milestone_id` | string | Yes | Identifier of the milestone |
| `milestone_title` | string | Yes | Human-readable milestone title |
| `allocated_amount` | string | No | Amount this event allocates to the milestone, in the organization's reporting currency |

### IPFS-Anchored Storage

When a bundle is stored off-chain, `data` is a manifest pointing to the IPFS document:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Manifest identifier |
| `ipfs_cid` | string | Yes | IPFS CID (CIDv1 base32 or CIDv0 base58btc) of the off-chain document |
| `interval` | string | Yes | Reporting interval (`DAILY`, `WEEKLY`, `MONTHLY`, `QUARTERLY`, `YEARLY`) |
| `date` | string | Yes | Bundle date in ISO 8601 format |
| `event_count` | integer | Yes | Number of events in the referenced document |

The referenced off-chain document carries `org_id`, `currency_id`, `version`, `date`, and the `events` array.

### Example: Funding record (Spending allocated to a sub-project)

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
      "creation_slot": 12345,
      "timestamp": "2025-06-01T10:15:30Z",
      "version": "1.0"
    },
    "type": "FUNDING",
    "data": [
      {
        "id": "event1",
        "type": "SPENDING",
        "funding_tx": "ftx1",
        "funding_id": "fund1",
        "amount_rcy": "85",
        "amount_fcy": "100",
        "vendor": "Vendor AB",
        "spending_category": "Personnel",
        "fx_rate": "0.85",
        "hash": "doc-hash-1",
        "notes": "Invoice #1",
        "date": "2025-04-03",
        "currency": {
          "id": "ISO_4217:EUR",
          "cust_code": "EUR"
        },
        "allocation": [
          {
            "project_id": "ProjectID1",
            "project_title": "ProjectTitle",
            "sub_project": {
              "sub_project_id": "SubProjectID1",
              "sub_project_title": "SubProjectTitle",
              "milestones": [
                {
                  "milestone_id": "ms1",
                  "milestone_title": "Milestone AB",
                  "allocated_amount": "85"
                }
              ]
            }
          }
        ]
      }
    ]
  }
}
```

### Example: Funding record (Funding allocated directly to a project)

```json
{
  <General structure>,
  "type": "FUNDING",
  "data": [
    {
      "id": "event2",
      "type": "FUNDING",
      "funding_tx": "ftx1",
      "funding_id": "fund1",
      "funding_entity": "FundingEntity",
      "allocation": [
        {
          "project_id": "ProjectID1",
          "project_title": "ProjectTitle",
          "milestones": [
            {
              "milestone_id": "ms1",
              "milestone_title": "Milestone AB",
              "allocated_amount": "100"
            }
          ]
        }
      ]
    }
  ]
}
```

## Type: Document

The `DOCUMENT` type anchors an **end-to-end-encrypted document** published by an organisation. The encrypted
envelope itself is stored on IPFS; the on-chain record is a manifest referencing it. The operator and the
public can verify integrity (hashes, CID) but can never read content — decryption keys exist only on the
recipients' devices.

### On-chain manifest (`data`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Server-assigned document identifier (UUID) |
| `ipfs_cid` | string | Yes | IPFS CID of the encrypted envelope document |
| `content_hash` | string | Yes | SHA-256 of the raw ciphertext bytes (hex) |
| `plaintext_hash` | string | Yes | SHA-256 commitment over the plaintext, computed client-side (hex) |
| `envelope_version` | integer | Yes | Envelope wire-format version |
| `slot_count` | integer | Yes | Number of recipient slots in the referenced envelope |
| `recipient_key_hashes` | array of string | Yes | One SHA-256 recipient key hash per slot (see [Recipient key hashes](#recipient-key-hashes)). Length equals `slot_count`, and entry `i` corresponds to `slots[i]` in the envelope. Present from metadata version `1.1` onward. |

### IPFS envelope document

The document stored at `ipfs_cid` is JSON with this structure:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `version` | integer | Yes | Envelope wire-format version (currently `1`), matching the manifest's `envelope_version` |
| `type` | string | Yes | Always `"REEVE_ENCRYPTED_DOCUMENT"` |
| `org_id` | string | Yes | Publishing organisation's id, matching the on-chain `org.id` |
| `content_hash` | string | Yes | SHA-256 of the raw ciphertext bytes (hex), matching the manifest |
| `plaintext_hash` | string | Yes | SHA-256 commitment over the plaintext (hex), matching the manifest |
| `payload` | object | Yes | The ciphertext and its nonce (see below) |
| `slots` | array | Yes | One entry per recipient (see below); length equals the manifest's `slot_count` |

#### `payload` object

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `ciphertext` | string | Yes | The encrypted document, base64 |
| `nonce` | string | Yes | The AEAD nonce for `ciphertext` |

#### `slots[]` entry

Each slot holds the material one recipient needs to unwrap the document encryption key, and nothing else.
There are **no recipient identifiers inside the envelope**: a recipient locates their slot either by its
index in the manifest's `recipient_key_hashes`, or by trial decryption.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `ephemeral_pub` | string | Yes | Per-slot ephemeral X25519 public key, 32 bytes hex |
| `wrapped_dek` | string | Yes | The document encryption key, AES-256-GCM-wrapped under an ECDH-derived slot KEK |

The organisation-internal identifiers a slot carries *inside* a Reeve deployment (`key_id`,
`recipient_ref`) are stripped before publication and appear neither here nor on-chain. Neither do e-mail
addresses, recipient names or labels, or file names.

### Example: IPFS envelope document

```json
{
  "version": 1,
  "type": "REEVE_ENCRYPTED_DOCUMENT",
  "org_id": "75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
  "content_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "plaintext_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752",
  "payload": {
    "ciphertext": "Y2lwaGVydGV4dA==",
    "nonce": "cccccccccccccccccccccccc"
  },
  "slots": [
    {
      "ephemeral_pub": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
      "wrapped_dek": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    },
    {
      "ephemeral_pub": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
      "wrapped_dek": "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    }
  ]
}
```

### Recipient key hashes

A recipient is the holder of an X25519 key pair. Their on-chain identifier is:

```
recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex X25519 public key )
```

rendered as 64 lowercase hex characters. No salt, no domain-separation prefix, no truncation — so any
third party can reproduce it from a public key with one command:

```console
$ printf %s 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a | xxd -r -p | sha256sum
300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae
```

SHA-256 rather than the SHA3-256 used for `org.id`, because readers recompute this in a browser and
WebCrypto implements no SHA-3 member.

**Reference vectors** (the RFC 7748 §6.1 X25519 public keys):

| X25519 public key | `recipient_key_hash` |
|---|---|
| `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a` | `300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae` |
| `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f` | `f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4` |

The list is what lets a public indexer answer "which documents are addressed to this key?" without
decrypting anything.

> **Privacy: this makes published documents linkable to their recipients.** A recipient key hash is a
> stable, public, permanent identifier. Anyone holding a person's X25519 public key — and key cards
> carrying public keys are exchanged by design — can compute their hash and enumerate every document
> ever addressed to them, across every organisation, for as long as the chain exists. The hash cannot be
> revoked, rotated away from, or deleted. This is a deliberate trade-off accepted in exchange for
> recipient-side filtering, and it replaces this format's earlier property of carrying no recipient
> identifiers at all. Everything else is unchanged: no e-mail addresses, recipient names or labels, or
> file names appear in either the manifest or the envelope, and no content is readable by anyone but a
> key holder.

> **Note on validation**: as with `FUNDING` manifests, several rules are enforced programmatically:
> `org_id` in the IPFS document matching the on-chain `org.id`, `content_hash` matching the decoded
> `payload.ciphertext`, the CID matching the document bytes, `slot_count` matching `slots.length`, and
> `recipient_key_hashes.length` matching `slot_count`.

### Example: Document record

> **`metadata` carries the version alone for this type.** `creation_slot` and `timestamp` are omitted
> from a DOCUMENT manifest on purpose: both are decided at dispatch — the slot needs a live chain tip,
> the timestamp is the publisher's clock — so a holder's wallet asked to attest the document *before*
> it is published cannot reproduce either, and therefore could not commit to a manifest containing
> them. Consumers should take both from the containing block instead, which is more trustworthy
> anyway: block slot and block time cannot be influenced by the publisher, whereas these two fields
> were publisher-supplied and could say anything. Every other publishable type still carries them.

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
      "version": "1.1"
    },
    "type": "DOCUMENT",
    "data": {
      "id": "0b0f7d1e-6f0a-4d9e-9d5e-1c2b3a4d5e6f",
      "ipfs_cid": "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi",
      "content_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "plaintext_hash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752",
      "envelope_version": 1,
      "slot_count": 2,
      "recipient_key_hashes": [
        "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae",
        "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b4"
      ]
    }
  }
}
```

### Metadata versions

| Version | Change |
|---------|--------|
| `1.0` | Initial `DOCUMENT` manifest. |
| `1.1` | Adds `recipient_key_hashes`. Documents anchored at `1.0` carry no hashes and can never match a recipient filter; the chain is immutable, so they are not backfilled. |

## Glossary

This section defines key terms used throughout the on-chain metadata format.

### Cost Center

A cost center is an organizational unit used for internal accounting and cost allocation. It helps organizations track expenses by department, project, or functional area. Cost centers are identified by a code and name combination.

**Example**: `"7777777:Foundation"` where `7777777` is the cost center code and `Foundation` is the cost center name.

### Counterparty

A counterparty is another entity involved in a transaction or business relationship. The counterparty field identifies the organization or individual on the other side of a transaction using their hashed organization ID or name.

**Example**: `"75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"` (hashed organization identifier)

### FX Rate

The foreign exchange (FX) rate is the conversion rate from an item's currency to the organization's reporting/functional currency at the time of the transaction. It provides transparency about the exchange rate applied to multi-currency entries.

**Format**: a decimal string. Cardano transaction metadata cannot encode floating-point numbers, so every amount and rate is stored as a string.

**Example**: `"0.85"` indicates 1 unit of the item currency equals 0.85 units of the reporting currency.

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
