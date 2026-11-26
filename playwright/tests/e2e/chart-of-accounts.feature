Feature: Manager can add and maintain the organization chart of accounts, new active accounts
  must be recognized during the transaction import

  Scenario: Chart of account is created with all the required fields
    Given Manager user wants to add a new chart of account
    And the manager fills the chart of account with all the required fields
    When system get the chart of account creation request
    Then the chart of account should be created with the provided data

  Scenario: Chart of account with an existing number is rejected
    Given Manager user wants to add a new chart of account
    And the chart of account number already exists in the system
    When system get the chart of account creation request
    Then the chart of account creation should be rejected by "CHART_OF_ACCOUNT_ALREADY_EXISTS"

  Scenario: Chart of account description is updated
    Given Manager user has an existing chart of account
    And the manager changes the chart of account description
    When system get the chart of account update request
    Then the chart of account should be updated with the new description

  Scenario: Deactivated chart of account is not listed as active
    Given Manager user has an existing chart of account
    And the manager deactivates the chart of account
    When system get the chart of account update request
    Then the chart of account should not be listed as active

  Scenario: Updating a non existing chart of account is rejected
    Given Manager user wants to update a chart of account
    And the chart of account number doesn't exist in the system
    When system get the chart of account update request
    Then the chart of account update should be rejected by "CHART_OF_ACCOUNT_NOT_FOUND"

  Scenario: New chart of account is valid for imported transactions
    Given Manager user has added a new chart of account
    And the manager creates the transaction CSV file using the new chart of account
    And system get the validation request
    When system get import request
    Then the transaction data should be imported with ready to approve status
    And the transaction should contain the new chart of account
