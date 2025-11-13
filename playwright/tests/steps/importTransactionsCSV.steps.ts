import {APIResponse, expect} from '@playwright/test';
import {createBdd} from 'playwright-bdd'
import {reeveService} from "../../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
import {transactionsBuilder} from "../../helpers/transactionsBuilder";
import {BatchesStatusCodes} from "../../api/api-helpers/batches-status-codes";
import {TransactionItemCsvDto} from "../../api/dtos/transactionItemCsvDto";
import {BatchResponse} from "../../api/dtos/batchDto";
import {transactionValidator} from "../../validators/transactionValidator";
import {TransactionPendingStatus} from "../../helpers/transaction-pending-status";
import {deleteFile} from "../../utils/csvFileGenerator";

const {Given, When, Then} = createBdd();
let authToken: string
let transactionCSVFile: string;
let transactionDataToImport: TransactionItemCsvDto[] = [];
Given(/^Manager user wants to import a transaction with a CSV file$/, async ({request}) => {
    const loginResponse = await (await reeveService(request)).loginManager()
    expect(loginResponse.status()).toEqual(HttpStatusCodes.success)
    authToken = (await loginResponse.json()).token_type + " " + (await loginResponse.json()).access_token;
});
Given(/^the manager creates the CSV file with all the required fields$/, async ({request}) => {
    transactionCSVFile = await (await transactionsBuilder(request, authToken))
        .createReadyToApproveTransaction(transactionDataToImport);
});
Given(/^system get the validation request$/, async ({request}) => {
    const validateResponse = await (await reeveService(request)).validateTransactionCsvFile(authToken,
        transactionCSVFile);
    expect(validateResponse.status()).toEqual(HttpStatusCodes.success);
});
When(/^system get import request$/, async ({request}) => {
    const importTxCsvResponse = await (await reeveService(request)).importTransactionCsvFile(authToken,
        transactionCSVFile);
    expect(importTxCsvResponse.status()).toEqual(HttpStatusCodes.RequestAccepted);
    await deleteFile(transactionCSVFile)
});
Then(/^the transaction data should be imported with ready to approve status$/, async ({request}) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatch(authToken,
        BatchesStatusCodes.APPROVE, transactionDataToImport[0].TxNumber);
    const batchDetailsResponse = await (await reeveService(request)).getBatchById(authToken,
        newBatchAfterImport.id);
    expect(batchDetailsResponse.status()).toEqual(HttpStatusCodes.success);
    let importedBatchDetails: BatchResponse =  await batchDetailsResponse.json()
    await (await transactionValidator()).validateImportedTxWithStatus(transactionDataToImport, importedBatchDetails,
        BatchesStatusCodes.APPROVE);
});
Given(/^the cost center data in the CSV file doesn't exist in the system$/, async ({request}) => {
    transactionCSVFile = await (await transactionsBuilder(request, authToken))
        .createCSVTransactionPending(transactionDataToImport, TransactionPendingStatus.COST_CENTER_DATA_NOT_FOUND);
});
Then(/^the system should create the transaction with pending status by "([^"]*)"$/, async ({request}, reason) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatch(authToken,
        BatchesStatusCodes.PENDING, transactionDataToImport[0].TxNumber);
    await (await transactionValidator()).validateImportedTxWithStatus(transactionDataToImport, newBatchAfterImport,
        BatchesStatusCodes.PENDING);
    if(reason == TransactionPendingStatus.COST_CENTER_DATA_NOT_FOUND){
        await (await transactionValidator()).validatePendingCondition(newBatchAfterImport,
            TransactionPendingStatus.COST_CENTER_DATA_NOT_FOUND)
    }
    if(reason == TransactionPendingStatus.VAT_DATA_NOT_FOUND){
        await (await transactionValidator()).validatePendingCondition(newBatchAfterImport,
            TransactionPendingStatus.VAT_DATA_NOT_FOUND)
    }
    if(reason == TransactionPendingStatus.CHART_OF_ACCOUNT_NOT_FOUND){
        await (await transactionValidator()).validatePendingCondition(newBatchAfterImport,
            TransactionPendingStatus.CHART_OF_ACCOUNT_NOT_FOUND)
    }
});
Given(/^the vat code data in the CSV file doesn't exist in the system$/, async ({request}) => {
    transactionCSVFile = await (await transactionsBuilder(request, authToken))
        .createCSVTransactionPending(transactionDataToImport, TransactionPendingStatus.VAT_DATA_NOT_FOUND);
});
Given(/^the chart of account code data in the CSV file doesn't exist in the system$/, async ({request}) => {
    transactionCSVFile = await (await transactionsBuilder(request, authToken))
        .createCSVTransactionPending(transactionDataToImport, TransactionPendingStatus.CHART_OF_ACCOUNT_NOT_FOUND);
});