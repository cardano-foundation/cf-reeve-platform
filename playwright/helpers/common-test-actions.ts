import {APIRequestContext, expect} from "@playwright/test";
import {reeveService} from "../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../api/api-helpers/http-status-codes";
import {transactionsBuilder} from "./transactionsBuilder";
import {TransactionItemCsvDto} from "../api/dtos/transactionItemCsvDto";
import {deleteFile} from "../utils/csvFileGenerator";
import {BatchesStatusCodes} from "../api/api-helpers/batches-status-codes";
import {transactionValidator} from "../validators/transactionValidator";
import {UpdateChartOfAccountsDto} from "../api/dtos/chartOfAccountsDto";
import {chartOfAccountsBuilder} from "./chartOfAccountsBuilder";

export async function commonTestActions(request: APIRequestContext) {
    const loginUser = async () => {
        const loginResponse = await (await reeveService(request)).loginManager()
        expect(loginResponse.status()).toEqual(HttpStatusCodes.success)
        return (await loginResponse.json()).token_type + " " + (await loginResponse.json()).access_token;

    }
    const importReadyToApproveTx = async (authToken: string, transactionDataToImport: TransactionItemCsvDto[]) => {
        const transactionCSVFile = await (await transactionsBuilder(request, authToken))
            .createReadyToApproveTransaction(transactionDataToImport);
        const validateResponse = await (await reeveService(request)).validateTransactionCsvFile(authToken,
            transactionCSVFile);
        expect(validateResponse.status()).toEqual(HttpStatusCodes.success);
        const importTxCsvResponse = await (await reeveService(request)).importTransactionCsvFile(authToken,
            transactionCSVFile);
        expect(importTxCsvResponse.status()).toEqual(HttpStatusCodes.RequestAccepted);
        await deleteFile(transactionCSVFile)
        const newBatchAfterImport = await (await reeveService(request)).getNewBatch(authToken,
            BatchesStatusCodes.APPROVE, transactionDataToImport[0].TxNumber);
        await (await transactionValidator()).validateImportedTxWithStatus(transactionDataToImport, newBatchAfterImport,
            BatchesStatusCodes.APPROVE);
        return newBatchAfterImport
    }

    const importAndGetPendingBatch = async (authToken: string, csvFile: string, txNumber: string) => {
        const validateResponse = await (await reeveService(request)).validateTransactionCsvFile(authToken, csvFile);
        expect(validateResponse.status()).toEqual(HttpStatusCodes.success);
        const importTxCsvResponse = await (await reeveService(request)).importTransactionCsvFile(authToken, csvFile);
        expect(importTxCsvResponse.status()).toEqual(HttpStatusCodes.RequestAccepted);
        await deleteFile(csvFile);
        return await (await reeveService(request)).getNewBatch(authToken, BatchesStatusCodes.PENDING, txNumber);
    }

    /**
     * Precondition action: add a new chart of account with all the required fields
     * @return the data of the created chart of account
     */
    const addChartOfAccount = async (authToken: string) => {
        const chartOfAccount: UpdateChartOfAccountsDto = await (await chartOfAccountsBuilder(request, authToken))
            .createChartOfAccountData();
        const createResponse = await (await reeveService(request)).createChartOfAccounts(authToken, chartOfAccount);
        expect(createResponse.status(), "Precondition chart of account " + chartOfAccount.customerCode
            + " should be created: " + await createResponse.text()).toEqual(HttpStatusCodes.success);
        return chartOfAccount
    }

    return {
        loginUser,
        addChartOfAccount,
        importReadyToApproveTx,
        importAndGetPendingBatch
    }
}