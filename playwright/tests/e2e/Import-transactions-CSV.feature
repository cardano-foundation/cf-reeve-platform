Feature: Users can import transactions into Reeve with a CSV file, system validates the structure file
  and import the transactions to be processed by the validation rules

  Scenario: Import ready to approve transaction
    Given Manager user wants to import a transaction with a CSV file
    And the manager creates the CSV file with all the required fields
    And system get the validation request
    When system get import request
    Then the transaction data should be imported with ready to approve status

  Scenario: Import transaction in pending status by unknown cost center
    Given Manager user wants to import a transaction with a CSV file
    And the cost center data in the CSV file doesn't exist in the system
    And system get the validation request
    When system get import request
    Then the system should create the transaction with pending status by "COST_CENTER_DATA_NOT_FOUND"

  Scenario: Import transaction in pending status by unknown VAT code
    Given Manager user wants to import a transaction with a CSV file
    And the vat code data in the CSV file doesn't exist in the system
    And system get the validation request
    When system get import request
    Then the system should create the transaction with pending status by "VAT_DATA_NOT_FOUND"

  Scenario: Import transaction in pending status by unknown Chart of account code
    Given Manager user wants to import a transaction with a CSV file
    And the chart of account code data in the CSV file doesn't exist in the system
    And system get the validation request
    When system get import request
    Then the system should create the transaction with pending status by "CHART_OF_ACCOUNT_NOT_FOUND"