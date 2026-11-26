Feature: Users can reprocess transactions that are in pending status once the issue is solved, system checks
  reevaluate pending reason to change transaction status.

  Scenario: Reprocess transaction in pending status by cost center mapping
    Given there is an imported transaction in pending status due to cost center mapping
    And the issue with the cost center is adjusted in the system
    When the system process the reprocess request
    Then the transaction should change the status to ready to approve

  Scenario: Reprocess transaction sent to pending by parent cost center mapping
    Given there is a ready to approve transaction
    And the transaction is reject due to issue with parent cost center mapping
    When the system process the reprocess request
    Then the transaction should change the status to ready to approve

  Scenario: Reprocess approved transaction sent to pending by parent cost center mapping
    Given there is an approved transaction
    And the transaction is reject due to issue with parent cost center mapping
    When the system process the reprocess request
    Then the transaction should change the status to ready to approve

  Scenario: Reprocess transaction in pending status by unknown account
    Given there is an imported transaction in pending status due to unknown account
    And the issue with the account is adjusted in the system
    When the system process the reprocess request
    Then the transaction should change the status to ready to approve

  Scenario: Reprocess transaction sent to pending by parent project code mapping
    Given there is a ready to approve transaction
    And the transaction is reject due to issue with project code mapping
    When the system process the reprocess request
    Then the transaction should change the status to ready to approve