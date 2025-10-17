import {APIResponse, expect} from '@playwright/test';
import {createBdd} from 'playwright-bdd'
import {reeveService} from "../../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
import {saveCSV} from "../../utils/csvFileGenerator";
import {transactionsBuilder} from "../../helpers/transactionsBuilder";
import {BatchData} from "../../api/dtos/batchsDto";
import {BatchesStatusCodes} from "../../api/api-helpers/batches-status-codes";
import {log} from "../../utils/logger";
import {TransactionItemCsvDto} from "../../api/dtos/transactionItemCsvDto";
import {BatchResponse} from "../../api/dtos/batchDto";
import {transactionValidator} from "../../validators/transactionValidator";

const {Given, When, Then} = createBdd();
let authToken: string
let transactionCSVFile: string;
let batchesBeforeImport: BatchData;
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
    const batchesResponse = await (await reeveService(request)).getBatchesByStatus(authToken,
        BatchesStatusCodes.APPROVE);
    expect(batchesResponse.status()).toEqual(HttpStatusCodes.success);
    batchesBeforeImport = await batchesResponse.json()
    const validateResponse = await (await reeveService(request)).validateTransactionCsvFile(authToken,
        transactionCSVFile);
    expect(validateResponse.status()).toEqual(HttpStatusCodes.success);


});
When(/^system get import request$/, async ({request}) => {
    const importTxCsvResponse = await (await reeveService(request)).importTransactionCsvFile(authToken,
        transactionCSVFile);
    expect(importTxCsvResponse.status()).toEqual(HttpStatusCodes.RequestAccepted);
});
Then(/^the transaction data should be imported with ready to approve status$/, async ({request}) => {
    const newBatchAfterImport = await (await reeveService(request)).getNewBatch(authToken,
        BatchesStatusCodes.APPROVE, batchesBeforeImport);
    const batchDetailsResponse = await (await reeveService(request)).getBatchById(authToken,
        newBatchAfterImport.id);
    expect(batchDetailsResponse.status()).toEqual(HttpStatusCodes.success);
    let importedBatchDetails: BatchResponse =  await batchDetailsResponse.json()
    await (await transactionValidator()).validateSingleReadyToApproveTx(transactionDataToImport, importedBatchDetails);
});