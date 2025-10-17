// Generated from: tests/e2e/login.feature
import { test } from "playwright-bdd";

test.describe('Login and authentication process tests', () => {

  test('Manager user can login with its credentials', async ({ Given, When, Then, page, request }) => { 
    await Given('Manager user wants to login into Reeve', null, { page }); 
    await When('system get the login request', null, { request }); 
    await Then('system should return success login response with authorization token', null, { page }); 
  });

  test('Manager user can not login with invalid credentials', async ({ Given, When, Then, request }) => { 
    await Given('Manager user wants to login into Reeve with wrong credentials'); 
    await When('system get the login request', null, { request }); 
    await Then('system should reject access'); 
  });

});

// == technical section ==

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('tests/e2e/login.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":3,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":4,"keywordType":"Context","textWithKeyword":"Given Manager user wants to login into Reeve","stepMatchArguments":[]},{"pwStepLine":8,"gherkinStepLine":5,"keywordType":"Action","textWithKeyword":"When system get the login request","stepMatchArguments":[]},{"pwStepLine":9,"gherkinStepLine":6,"keywordType":"Outcome","textWithKeyword":"Then system should return success login response with authorization token","stepMatchArguments":[]}]},
  {"pwTestLine":12,"pickleLine":8,"tags":[],"steps":[{"pwStepLine":13,"gherkinStepLine":9,"keywordType":"Context","textWithKeyword":"Given Manager user wants to login into Reeve with wrong credentials","stepMatchArguments":[]},{"pwStepLine":14,"gherkinStepLine":10,"keywordType":"Action","textWithKeyword":"When system get the login request","stepMatchArguments":[]},{"pwStepLine":15,"gherkinStepLine":11,"keywordType":"Outcome","textWithKeyword":"Then system should reject access","stepMatchArguments":[]}]},
]; // bdd-data-end