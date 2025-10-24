Feature: Users can import transactions into Reeve with a CSV file, system validates the structure file
  and import the transactions to be processed by the validation rules

  Scenario: Import ready to approve transaction
    Given Manager user wants to import a transaction with a CSV file
    And the manager creates the CSV file with all the required fields
    And system get the validation request
    When system get import request
    Then the transaction data should be imported with ready to approve status