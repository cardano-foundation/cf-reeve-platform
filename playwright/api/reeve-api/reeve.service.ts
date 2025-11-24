import {APIRequestContext, APIResponse, expect} from "@playwright/test";
import {reeveApi} from "./reeve.api";
import {Batch, BatchData} from "../dtos/batchsDto";
import {BatchesStatusCodes} from "../api-helpers/batches-status-codes";
import {BatchResponse} from "../dtos/batchDto";
import {log} from "../../utils/logger";

let managerUser = process.env.MANAGER_USER as string;
let managerPassword = process.env.MANAGER_PASSWORD as string;
let organizationId = process.env.ORGANIZATION_ID as string;
export async function reeveService(request: APIRequestContext) {
    const loginToReeve = async (userName: string, password: string) => {
        return await reeveApi(request).loginReeve(userName, password);
    };

    const loginManager = async () => {
        return await reeveApi(request).loginReeve(managerUser, managerPassword);
    }

    const getTransactionTypes = async (authToken: string) => {
        return await reeveApi(request).transactionTypes(authToken);
    }

    const getEventCodes = async (authToken: string) => {
        return await reeveApi(request).eventCodes(organizationId, authToken);
    }

    const getChartOfAccounts = async (authToken: string) => {
        return await reeveApi(request).chartOfAccounts(organizationId, authToken);
    }

    const validateTransactionCsvFile = async (authToken: string, transactionFile: string) => {
        return await reeveApi(request).validateTransactionCsvFile(organizationId, authToken, transactionFile);
    }

    const importTransactionCsvFile = async (authToken: string, transactionFile: string) => {
        return await reeveApi(request).importTransactionCsvFile(organizationId,authToken, transactionFile)
    }

    const getBatchesByStatus = async (authToken: string, status: string) => {
        return await reeveApi(request).batchesByStatus(organizationId, authToken, status);
    }

    const getNewBatch = async (authToken: string, status: string, txNumber: string) => {
        let batchesResponse: APIResponse;
        let batchesAfterImport: BatchData;
        let batchId: BatchResponse;
        await expect.poll(async () => {
            batchesResponse = await (await reeveService(request)).getBatchesByStatus(authToken,
                status);
            batchesAfterImport = await batchesResponse.json()
            let batchesIdAfterImport = batchesAfterImport.batchs.map(batch => batch.id);
            batchId = await findBatchWithTxNumber(batchesIdAfterImport,txNumber,authToken);
            return batchId
        },{
            message: "The new Batch was not created: ",
            intervals: [1_000, 2_000, 10_000],
            timeout: 280_000
        }).not.toBeNull();
        return batchId
    }

    const getBatchById = async (authToken: string, batchId: string) => {
        return await reeveApi(request).batchById(authToken, batchId)
    }

    const findBatchWithTxNumber = async (batchesIdAfterImport: string[], txNumber: string, authToken: string) => {
        for (const batchId of batchesIdAfterImport) {
            const batchDetailsResponse: BatchResponse = await (await getBatchById(authToken, batchId)).json();
            if (batchDetailsResponse.transactions[0].internalTransactionNumber == txNumber){
                return batchDetailsResponse
            }
        }
        return null;
    }

    return {
        loginToReeve,
        loginManager,
        getTransactionTypes,
        getEventCodes,
        getChartOfAccounts,
        validateTransactionCsvFile,
        importTransactionCsvFile,
        getBatchesByStatus,
        getNewBatch,
        getBatchById
    };

}