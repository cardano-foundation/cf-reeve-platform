import {APIRequestContext} from "@playwright/test";

import * as BaseApi from "../base.api";
import * as Endpoints from "../api-helpers/enpoints";
import {getDateInThePast} from "../../utils/dateGenerator";
import * as fs from "fs";
import * as path from "node:path";
import {RejectTransactionDto} from "../dtos/RejectTransactionDto";
import {UpdateCostCenterDto} from "../dtos/costCenterDto";
import {ApproveTransactionDto} from "../dtos/approveTransactionDto";
import {UpdateChartOfAccountsDto} from "../dtos/chartOfAccountsDto";

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

    const chartOfAccounts = async (organizationId: string, authToken: string, customerCode?: string) => {
        const params: Record<string, any> = { active: true };
        if (customerCode) params.customerCode = customerCode;
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Organization.ChartOfAccounts.replace(":orgId", organizationId),
            params,
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/x-www-form-urlencoded",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const getCostCenters = async (organizationId: string, authToken: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Organization.CostCenters.replace(":orgId", organizationId),
            {},
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const updateCostCenter = async (organizationId: string, authToken: string, costCenter: UpdateCostCenterDto) => {
        return BaseApi.putData(
            request,
            Endpoints.Reeve.Organization.CostCenters.replace(":orgId", organizationId),
            costCenter,
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const updateChartOfAccounts = async (organizationId: string, authToken: string, chartOfAccount: UpdateChartOfAccountsDto) => {
        return BaseApi.putData(
            request,
            Endpoints.Reeve.Organization.ChartOfAccounts.replace(":orgId", organizationId),
            chartOfAccount,
            {
                Accept: "application/json",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const createChartOfAccounts = async (organizationId: string, authToken: string, chartOfAccount: UpdateChartOfAccountsDto) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Organization.ChartOfAccounts.replace(":orgId", organizationId),
            chartOfAccount,
            {
                Accept: "application/json",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            {},
            logApiResponse
        )
    }

    const chartTypes = async (organizationId: string, authToken: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Organization.ChartTypes.replace(":orgId", organizationId),
            {},
            {
                Accept: "application/json",
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
    const batchById = async (organizationId: string, authToken: string, batchId: string) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Batches.BatchById.replace(":batchId",batchId),
            {
                organisationId: organizationId
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
    const reprocessBatch = async (organizationId: string, authToken: string, batchId: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Batches.Reprocess.replace(":batchId", batchId),
            {
                organisationId: organizationId
            },
            {
                Accept: "application/json",
                "Accept-Encoding": "gzip, deflate, br",
                Authorization: authToken
            },
            logApiResponse
        )
    }

    const approveTransaction = async (authToken: string, approvePayload: ApproveTransactionDto) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Transactions.Approve,
            approvePayload,
            {
                Accept: "application/json",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            {},
            logApiResponse
        )
    }

    const rejectTransaction = async (authToken: string, transactionToReject: RejectTransactionDto) => {
        return BaseApi.postData(
            request,
            Endpoints.Reeve.Transactions.Reject,
            transactionToReject,
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            {},
            logApiResponse
        )
    }
    const getTransactionById = async (organizationId: string, authToken: string, transactionId: string) => {
        return BaseApi.getData(
            request,
            Endpoints.Reeve.Transactions.transactionById.replace(":txId", transactionId),
            {
                organisationId: organizationId
            },
            {
                Accept: "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Content-Type": "application/json",
                Authorization: authToken
            },
            logApiResponse
        )
    }
    return {
        loginReeve,
        transactionTypes,
        eventCodes,
        chartOfAccounts,
        getCostCenters,
        updateCostCenter,
        updateChartOfAccounts,
        createChartOfAccounts,
        chartTypes,
        validateTransactionCsvFile,
        importTransactionCsvFile,
        batchesByStatus,
        batchById,
        reprocessBatch,
        approveTransaction,
        rejectTransaction,
        getTransactionById
    };
}