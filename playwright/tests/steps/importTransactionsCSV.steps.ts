import {APIResponse, expect} from '@playwright/test';
import {Given, When, Then} from "../../utils/playwright-bdd-fixtures";
import {reeveService} from "../../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
import {transactionsBuilder} from "../../helpers/transactionsBuilder";
import {BatchesStatusCodes} from "../../api/api-helpers/batches-status-codes";
import {BatchResponse} from "../../api/dtos/batchDto";
import {transactionValidator} from "../../validators/transactionValidator";
import {TransactionPendingInvalidStatus} from "../../helpers/transaction-pending-invalid-status";
import {deleteFile} from "../../utils/csvFileGenerator";
import {commonTestActions} from "../../helpers/common-test-actions";

Given(/^Manager user wants to import a transaction with a CSV file$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser()
});
Given(/^the manager creates the CSV file with all the required fields$/, async ({request,ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createReadyToApproveTransaction(ctx.transaction.transactionDataToImport);
});
Given(/^system get the validation request$/, async ({request,ctx}) => {
    const validateResponse = await (await reeveService(request)).validateTransactionCsvFile(ctx.auth.authToken,
        ctx.transaction.transactionCSVFile);
    expect(validateResponse.status()).toEqual(HttpStatusCodes.success);
});
When(/^system get import request$/, async ({request, ctx}) => {
    const importTxCsvResponse = await (await reeveService(request)).importTransactionCsvFile(ctx.auth.authToken,
        ctx.transaction.transactionCSVFile);
    expect(importTxCsvResponse.status()).toEqual(HttpStatusCodes.RequestAccepted);
    await deleteFile(ctx.transaction.transactionCSVFile)
});
Then(/^the transaction data should be imported with ready to approve status$/, async ({request, ctx}) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatch(ctx.auth.authToken,
        BatchesStatusCodes.APPROVE, ctx.transaction.transactionDataToImport[0].TxNumber);
    const batchDetailsResponse = await (await reeveService(request)).getBatchById(ctx.auth.authToken,
        newBatchAfterImport.id);
    expect(batchDetailsResponse.status()).toEqual(HttpStatusCodes.success);
    let importedBatchDetails: BatchResponse =  await batchDetailsResponse.json()
    ctx.transaction.batchDetails = importedBatchDetails;
    await (await transactionValidator()).validateImportedTxWithStatus(ctx.transaction.transactionDataToImport, importedBatchDetails,
        BatchesStatusCodes.APPROVE);
});
Given(/^the cost center data in the CSV file doesn't exist in the system$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionPending(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.COST_CENTER_DATA_NOT_FOUND);
});
Then(/^the system should create the transaction with pending status by "([^"]*)"$/, async ({request, ctx}, reason) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatch(ctx.auth.authToken,
        BatchesStatusCodes.PENDING, ctx.transaction.transactionDataToImport[0].TxNumber);
    await (await transactionValidator()).validateImportedTxWithStatus(ctx.transaction.transactionDataToImport, newBatchAfterImport,
        BatchesStatusCodes.PENDING);
    await (await transactionValidator()).validatePendingCondition(newBatchAfterImport,
        reason)
});
Given(/^the vat code data in the CSV file doesn't exist in the system$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionPending(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.VAT_DATA_NOT_FOUND);
});
Given(/^the chart of account code data in the CSV file doesn't exist in the system$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionPending(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.CHART_OF_ACCOUNT_NOT_FOUND);
});
Given(/^the transaction amounts are not balanced$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionInvalid(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.UNBALANCED_TRANSACTION);
});
Then(/^the system should create the transaction with invalid status by "([^"]*)"$/, async ({request, ctx}, reason) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatchByDocumentNumber(ctx.auth.authToken,
        BatchesStatusCodes.INVALID, ctx.transaction.transactionDataToImport[0].DocumentName);
    await (await transactionValidator()).validateImportedTxWithStatus(ctx.transaction.transactionDataToImport, newBatchAfterImport,
        BatchesStatusCodes.INVALID);
    await (await transactionValidator()).validateInvalidCondition(newBatchAfterImport,
        reason)
});
Given(/^the transaction number is missing in the CSV file$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionInvalid(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.TX_INTERNAL_NUMBER_MUST_BE_PRESENT);

});
Given(/^the transaction debit code is missing in the CSV file$/, async ({request, ctx}) => {
    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionInvalid(ctx.transaction.transactionDataToImport, TransactionPendingInvalidStatus.ACCOUNT_CODE_DEBIT_IS_EMPTY);
});