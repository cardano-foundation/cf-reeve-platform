import {Given, When, Then} from "../../utils/playwright-bdd-fixtures";
import {reeveService} from "../../api/reeve-api/reeve.service";
import {commonTestActions} from "../../helpers/common-test-actions";
import {chartOfAccountsBuilder} from "../../helpers/chartOfAccountsBuilder";
import {transactionsBuilder} from "../../helpers/transactionsBuilder";
import {chartOfAccountValidator} from "../../validators/chartOfAccountValidator";
import {transactionValidator} from "../../validators/transactionValidator";

Given(/^Manager user wants to (?:add a new|update a) chart of account$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
});
Given(/^Manager user has (?:an existing|added a new) chart of account$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
    ctx.chartOfAccount.existingAccount = await (await commonTestActions(request)).addChartOfAccount(ctx.auth.authToken);
});
Given(/^the manager fills the chart of account with all the required fields$/, async ({request, ctx}) => {
    ctx.chartOfAccount.requestData = await (await chartOfAccountsBuilder(request, ctx.auth.authToken))
        .createChartOfAccountData();
});
Given(/^the chart of account number already exists in the system$/, async ({request, ctx}) => {
    ctx.chartOfAccount.existingAccount = await (await commonTestActions(request)).addChartOfAccount(ctx.auth.authToken);
    ctx.chartOfAccount.requestData = await (await chartOfAccountsBuilder(request, ctx.auth.authToken))
        .createChartOfAccountDataWithNumber(ctx.chartOfAccount.existingAccount.customerCode);
});
Given(/^the manager changes the chart of account description$/, async ({request, ctx}) => {
    ctx.chartOfAccount.requestData = (await chartOfAccountsBuilder(request, ctx.auth.authToken))
        .createChartOfAccountDataWithNewDescription(ctx.chartOfAccount.existingAccount);
});
Given(/^the manager deactivates the chart of account$/, async ({request, ctx}) => {
    ctx.chartOfAccount.requestData = (await chartOfAccountsBuilder(request, ctx.auth.authToken))
        .createDeactivatedChartOfAccountData(ctx.chartOfAccount.existingAccount);
});
Given(/^the chart of account number doesn't exist in the system$/, async ({request, ctx}) => {
    ctx.chartOfAccount.requestData = await (await chartOfAccountsBuilder(request, ctx.auth.authToken))
        .createChartOfAccountData();
});
Given(/^the manager creates the transaction CSV file using the new chart of account$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createReadyToApproveTransactionWithDebitAccount(ctx.transaction.transactionDataToImport, {
            accountCode: ctx.chartOfAccount.existingAccount.customerCode,
            referenceCode: ctx.chartOfAccount.existingAccount.eventRefCode,
            accountName: ctx.chartOfAccount.existingAccount.name
        });
});
When(/^system get the chart of account creation request$/, async ({request, ctx}) => {
    ctx.chartOfAccount.response = await (await reeveService(request)).createChartOfAccounts(ctx.auth.authToken,
        ctx.chartOfAccount.requestData);
});
When(/^system get the chart of account update request$/, async ({request, ctx}) => {
    ctx.chartOfAccount.response = await (await reeveService(request)).updateChartOfAccounts(ctx.auth.authToken,
        ctx.chartOfAccount.requestData);
});
Then(/^the chart of account should (?:be created with the provided data|be updated with the new description)$/,
    async ({request, ctx}) => {
        await (await chartOfAccountValidator()).validateSuccessfulResponse(ctx.chartOfAccount.response,
            ctx.chartOfAccount.requestData);
        const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken,
            ctx.chartOfAccount.requestData.customerCode);
        await (await chartOfAccountValidator()).validateStoredAccount(chartOfAccountsResponse,
            ctx.chartOfAccount.requestData);
    });
Then(/^the chart of account should not be listed as active$/, async ({request, ctx}) => {
    await (await chartOfAccountValidator()).validateSuccessfulResponse(ctx.chartOfAccount.response,
        ctx.chartOfAccount.requestData);
    const activeAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken,
        ctx.chartOfAccount.requestData.customerCode);
    await (await chartOfAccountValidator()).validateAccountNotListed(activeAccountsResponse,
        ctx.chartOfAccount.requestData.customerCode);
});
Then(/^the chart of account creation should be rejected by "([^"]*)"$/, async ({request, ctx}, reason) => {
    await (await chartOfAccountValidator()).validateRejectedRequest(ctx.chartOfAccount.response, reason);
    const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken,
        ctx.chartOfAccount.existingAccount.customerCode);
    await (await chartOfAccountValidator()).validateStoredAccount(chartOfAccountsResponse,
        ctx.chartOfAccount.existingAccount);
});
Then(/^the chart of account update should be rejected by "([^"]*)"$/, async ({request, ctx}, reason) => {
    await (await chartOfAccountValidator()).validateRejectedRequest(ctx.chartOfAccount.response, reason);
    const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken,
        ctx.chartOfAccount.requestData.customerCode);
    await (await chartOfAccountValidator()).validateAccountNotListed(chartOfAccountsResponse,
        ctx.chartOfAccount.requestData.customerCode);
});
Then(/^the transaction should contain the new chart of account$/, async ({ctx}) => {
    await (await transactionValidator()).validateTransactionContainsAccount(ctx.transaction.batchDetails,
        ctx.chartOfAccount.existingAccount.customerCode);
});
