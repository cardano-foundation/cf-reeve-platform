Feature: Users can reject transactions that has wrong data to avoid be published or to be correct it, system checks
  rejection reason to change transaction status.

  Scenario: Reject transaction to invalid by incorrect cost center
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by incorrect cost center
    When the system get the rejection request
    Then the transaction should be now in invalid status by "INCORRECT_COST_CENTER" reason

  Scenario: Reject transaction to invalid by incorrect amount
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by incorrect amount
    When the system get the rejection request
    Then the transaction should be now in invalid status by "INCORRECT_AMOUNT" reason

  Scenario: Reject transaction to invalid by incorrect vat code
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by incorrect vat code
    When the system get the rejection request
    Then the transaction should be now in invalid status by "INCORRECT_VAT_CODE" reason

  Scenario: Reject transaction to invalid by incorrect currency
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by incorrect currency
    When the system get the rejection request
    Then the transaction should be now in invalid status by "INCORRECT_CURRENCY" reason

  Scenario: Reject transaction to invalid by incorrect project
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by incorrect project
    When the system get the rejection request
    Then the transaction should be now in invalid status by "INCORRECT_PROJECT" reason

  Scenario: Reject transaction to pending by parent cost center
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by review parent cost center
    When the system get the rejection request
    Then the transaction should be now in pending status by "REVIEW_PARENT_COST_CENTER" reason

  Scenario: Reject transaction to pending by parent project code
    Given user import ready to approve transaction to the system
    And And the user wants to rejects the transaction by review parent project code
    When the system get the rejection request
    Then the transaction should be now in pending status by "REVIEW_PARENT_PROJECT_CODE" reason