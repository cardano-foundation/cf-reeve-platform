# On-Chain Format
For Reeve we are using a specific on-chain format, which is dealt under the label 1517.
The label was chosen in reference to the death year of [Luca Pacioli](https://en.wikipedia.org/wiki/Luca_Pacioli), the renowned accountant widely regarded as the father of accounting.

## General structure
The general structure contains the definition of the organisation and general metadata for the particular transactions.
The type is an identifier for the specific transaction type. From this type more fields can be derived. 
```json
{
  "1517": {
    "org": {
      "id": "string",
      "name": "string",
      "currency_id": "string",
      "country_code": "string",
      "tax_id_number": "string"
    },
    "metadata": {
      "creation_slot": "u64",
      "timestamp": "timestamp string (ISO-8601)",
      "version": "1.1"
    },
    "type": <Identifier of type>
  }
}
```

Currently we are supporting the following types:
- `INDIVIDUAL_TRANSACTIONS`
- `REPORT`

### Individual Transactions
Additionally to the general structure the type `INDIVIDUAL_TRANSACTIONS` hold the data of the published transactions:

```json
{
  <General structure>,
  "type": "INDIVIDUAL_TRANSACTIONS",
  "data": [
    {
      "id": "string",
      "number": "string",
      "batch_id": "string",
      "type": "TransactionType enum represented as a String",
      "date": "date string",
      "accounting_period": "string",
      "items": [
        {
          "id": "string",
          "amount": "string",
          "event": { // Optional
            "code": "string",
            "name": "string"
          },
          "project": { // Optional
            "cust_code": "string",
            "name": "string"
          },
          "cost_center": { // Optional
            "cust_code": "string",
            "name": "string"
          },
          "fx_rate": "string",
          "document": {
            "number": "string",
            "currency": {
              "id": "string",
              "cust_code": "string"
            },
            "vat": { // Optional
              "cust_code": "string",
              "rate": "string"
            },
            "counterparty": { // Optional
              "cust_code": "string",
              "type": "string"
            }
          }
        }, 
        ...
      ]
    },
    ...
  ]
}
```

### Report
The type `REPORT` is used to publish the financial reports. The subtype is used to differentiate between the different types of reports.
```json
{
  <General structure>,
  "type": "REPORT",
  "interval": "string",
  "year": "string",
  "mode": "string",
  "ver": "string",
  "period": "string", // Optional
  "subType": "string",
  "data": {
    <Subtype specific data>
  }
}
```
Currently supported are the following subtypes:
- `BALANCE_SHEET`
- `INCOME_STATEMENT`
#### Balance Sheet
```json
{
  <General Report structure>,
  "data": {
    "assets": {
      "non_current_assets": { // Optional, only if present
        "property_plant_equipment": "string",
        "intangible_assets": "string",
        "financial_assets": "string",
      },
      "current_assets": { // Optional, only if present
        "prepayments_and_other_short_term_assets": "string",
        "other_receivables": "string",
        "crypto_assets": "string",
        "cash_and_cash_equivalents": "string"
      }
    },
    "liabilities": {
      "non_current_liabilities": { // Optional, only if present
        "provisions": "string"
      },
      "current_liabilities": { // Optional, only if present 
        "trade_accounts_payables": "string",
        "other_current_liabilities": "string",
        "accruals_and_short_term_provisions": "string"
      }
    },
    "capital": {
      "capital": "string", // Optional, only if present
      "results_carried_forward": "string" // Optional, only if present
      "profit_of_the_year": "string" // Optional, only if present
    }
  }
}
```
#### Income Statement
```json
{
  <General Report structure>,
  "data": {
    "revenues": {
      "other_income": "string", // Optional, only if present
      "build_of_long_term_provision": "string" // Optional, only if present
    },
    "cost_of_goods_and_services" : {
      "cost_of_providing_services": "string" // Optional, only if present
    },
    "operating_expenses": {
      "personnel_expenses": "string", // Optional, only if present
      "general_and_adminsitrative_expenses": "string", // Optional, only if present
      "depreciation_and_impairment_losses_on_tangible_assets": "string", // Optional, only if present
      "amortization_on_intangible_assets": "string", // Optional, only if present
      "rent_expenses": "string" // Optional, only if present
    },
    "profit_for_the_year": "string", // Optional, only if present
    "extraordinary_income": {
        "extraordinary_expenses": "string" // Optional, only if present
    },
    "financial_income": {
      "financial_expenses": "string", // Optional, only if present
      "financial_revenues": "string", // Optional, only if present
      "staking_rewards_income": "string", // Optional, only if present
      "net_income_options_sale": "string", // Optional, only if present
      "realised_gain_on_sale_of_cryptocurrencies": "string" // Optional, only if present
    },
    "tax_expenses": {
      "income_tax_expenses": "string" // Optional, only if present
    }
  }
}
```