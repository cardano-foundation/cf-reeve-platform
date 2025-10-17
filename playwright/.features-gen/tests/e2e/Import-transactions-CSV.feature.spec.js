// Generated from: tests/e2e/Import-transactions-CSV.feature
import { test } from "playwright-bdd";

test.describe('Users can import transactions into Reeve with a CSV file, system validates the structure file', () => {

  test('Import ready to approve transaction', async ({ Given, When, Then, And, request }) => { 
    await Given('Manager user wants to import a transaction with a CSV file', null, { request }); 
    await And('the manager creates the CSV file with all the required fields', null, { request }); 
    await And('system get the validation request', null, { request }); 
    await When('system get import request', null, { request }); 
    await Then('the transaction data should be imported with ready to approve status', null, { request }); 
  });

});

// == technical section ==

test.use({
  $test: [({}, use) => use(test), { scope: 'test', box: true }],
  $uri: [({}, use) => use('tests/e2e/Import-transactions-CSV.feature'), { scope: 'test', box: true }],
  $bddFileData: [({}, use) => use(bddFileData), { scope: "test", box: true }],
});

const bddFileData = [ // bdd-data-start
  {"pwTestLine":6,"pickleLine":4,"tags":[],"steps":[{"pwStepLine":7,"gherkinStepLine":5,"keywordType":"Context","textWithKeyword":"Given Manager user wants to import a transaction with a CSV file","stepMatchArguments":[]},{"pwStepLine":8,"gherkinStepLine":6,"keywordType":"Context","textWithKeyword":"And the manager creates the CSV file with all the required fields","stepMatchArguments":[]},{"pwStepLine":9,"gherkinStepLine":7,"keywordType":"Context","textWithKeyword":"And system get the validation request","stepMatchArguments":[]},{"pwStepLine":10,"gherkinStepLine":8,"keywordType":"Action","textWithKeyword":"When system get import request","stepMatchArguments":[]},{"pwStepLine":11,"gherkinStepLine":9,"keywordType":"Outcome","textWithKeyword":"Then the transaction data should be imported with ready to approve status","stepMatchArguments":[]}]},
]; // bdd-data-end