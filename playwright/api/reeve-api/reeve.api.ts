import {APIRequestContext} from "@playwright/test";

import * as BaseApi from "../base.api";
import * as Endpoints from "../api-helpers/enpoints";
import {getDateInThePast} from "../../utils/dateGenerator";
import * as fs from "fs";
import * as path from "node:path";

export function reeveApi(request: APIRequestContext) {
    let logApiResponse = process.env.API_LOG_REQUEST == "true"
    const loginReeve = async (userName: string, password: string) => {
        return BaseApi.postForm(
            request,
            Endpoints.Reeve.SignIn.Base,
            {
                grant_type: "password",
                client_id: "webclient",
                username: userName,
                password: password
            },
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/x-www-form-urlencoded"
            },
            logApiResponse
        );
    };

    const transactionTypes = async (authToken: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Transactions.Types,
            {},
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/x-www-form-urlencoded",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const eventCodes = async (organizationId: string, authToken: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Organization.EventCodes.replace(":orgId", organizationId),
            {},
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/x-www-form-urlencoded",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const chartOfAccounts = async (organizationId: string, authToken: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Organization.ChartOfAccounts.replace(":orgId", organizationId),
            {},
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/x-www-form-urlencoded",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const validateTransactionCsvFile = async (organizationId: string, authToken: string, transactionFilePath: string) => {
        return BaseApi.postFormData(
            request,
            Endpoints.Reeve.Transactions.Validation,
            {
                organisationId: organizationId,
                extractorType: 'CSV',
                dateFrom: getDateInThePast(6, false),
                dateTo: getDateInThePast(2, false),
                file: {
                    name: path.basename(transactionFilePath),
                    mimeType: 'text/csv',
                    buffer: fs.readFileSync(transactionFilePath),
                }
            },
            {
                "Accept-Encoding": "gzip, deflate, br",
                'Authorization': authToken
            },
            logApiResponse
        );
    }

    const importTransactionCsvFile = async (organizationId: string, authToken: string, transactionFilePath: string) => {
        return BaseApi.postFormData(
            request,
            Endpoints.Reeve.Transactions.Extraction,
            {
                organisationId: organizationId,
                extractorType: 'CSV',
                dateFrom: getDateInThePast(6, false),
                dateTo: getDateInThePast(2, false),
                file: {
                    name: path.basename(transactionFilePath),
                    mimeType: 'text/csv',
                    buffer: fs.readFileSync(transactionFilePath),
                }
            },
            {
                "Accept-Encoding": "gzip, deflate, br",
                'Authorization': authToken
            },
            logApiResponse
        );
    }
    const batchesByStatus = async (organizationId: string, authToken: string, status: string) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Batches.Batches,
            {
                organisationId: organizationId,
                batchStatistics: [status]
            },
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            {
                page: 0,
                size: 100
            },
            logApiResponse
        )
    }
    const batchById = async (authToken: string, batchId: string) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Batches.BatchById.replace(":batchId",batchId),
            {},
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            {
                page: 0,
                size: 100
            },
            logApiResponse
        )
    }
    return {
        loginReeve,
        transactionTypes,
        eventCodes,
        chartOfAccounts,
        validateTransactionCsvFile,
        importTransactionCsvFile,
        batchesByStatus,
        batchById
    };
}