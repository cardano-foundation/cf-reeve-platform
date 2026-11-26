import {test as base, createBdd} from 'playwright-bdd';
import {APIResponse} from "@playwright/test";
import {TransactionItemCsvDto} from "../api/dtos/transactionItemCsvDto";
import {BatchResponse} from "../api/dtos/batchDto";
import {RejectTransactionDto, RejectTransactionResponseDto} from "../api/dtos/RejectTransactionDto";
import {UpdateChartOfAccountsDto} from "../api/dtos/chartOfAccountsDto";

interface AuthContext {
    authToken: string;
}

interface TransactionContext {
    batchDetails?: BatchResponse;

    transactionCSVFile?: string;
    transactionDataToImport: TransactionItemCsvDto[];

    rejectTransactionData?: RejectTransactionDto
    rejectTransactionResponse?: RejectTransactionResponseDto

}

interface CostCenterContext {
    costCenterCode?: string;
    costCenterName?: string;
}

interface ChartOfAccountContext {
    updateDto?: UpdateChartOfAccountsDto;
    existingAccount?: UpdateChartOfAccountsDto;
    requestData?: UpdateChartOfAccountsDto;
    response?: APIResponse;
}

interface ScenarioContext {
    auth: AuthContext
    transaction: TransactionContext
    costCenter: CostCenterContext
    chartOfAccount: ChartOfAccountContext
}

export const test = base.extend<{ ctx: ScenarioContext }>({
    ctx: async ({}, use) => {
        await use({
            auth: {
                authToken: '',
            },
            transaction: {
                transactionDataToImport: []
            },
            costCenter: {},
            chartOfAccount: {}
        });
    }
});

export const { Given, When, Then } = createBdd(test);