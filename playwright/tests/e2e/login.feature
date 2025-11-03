Feature: Login and authentication process tests

  Scenario: Manager user can login with its credentials
    Given Manager user wants to login into Reeve
    When system get the login request
    Then system should return success login response with authorization token

  Scenario: Manager user can not login with invalid credentials
    Given Manager user wants to login into Reeve with wrong credentials
    When system get the login request
    Then system should reject access