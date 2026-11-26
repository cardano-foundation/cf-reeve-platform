import {APIResponse, expect} from "@playwright/test";
import {HttpStatusCodes} from "../api/api-helpers/http-status-codes";
import {ChartOfAccountErrorCodes} from "../helpers/chart-of-account-error-codes";
import {ChartOfAccountsDto, UpdateChartOfAccountsDto} from "../api/dtos/chartOfAccountsDto";

const expectedStatusByError: Record<string, HttpStatusCodes> = {
    [ChartOfAccountErrorCodes.CHART_OF_ACCOUNT_ALREADY_EXISTS]: HttpStatusCodes.Conflict,
    [ChartOfAccountErrorCodes.CHART_OF_ACCOUNT_NOT_FOUND]: HttpStatusCodes.NotFound
}

export async function chartOfAccountValidator() {
    const validateSuccessfulResponse = async (response: APIResponse, expectedAccount: UpdateChartOfAccountsDto) => {
        expect(response.status(), "The request for the chart of account " + expectedAccount.customerCode
            + " should be successful: " + await response.text()).toEqual(HttpStatusCodes.success);
        const responseAccount: ChartOfAccountsDto = await response.json();
        expect(responseAccount.error, "The response should not contain an error").toBeNull();
        await validateAccountData(responseAccount, expectedAccount, "response");
    }

    /**
     * Validate that the chart of account is stored with the expected data
     * @param chartOfAccountsResponse response of the active chart of accounts requested by the account number
     */
    const validateStoredAccount = async (chartOfAccountsResponse: APIResponse, expectedAccount: UpdateChartOfAccountsDto) => {
        const storedAccounts = await getListedAccounts(chartOfAccountsResponse);
        expect(storedAccounts.length, "The chart of account " + expectedAccount.customerCode
            + " should be listed once in the system").toEqual(1);
        await validateAccountData(storedAccounts[0], expectedAccount, "stored");
    }

    const validateAccountData = async (account: ChartOfAccountsDto, expectedAccount: UpdateChartOfAccountsDto, source: string) => {
        expect(account.customerCode, "The " + source + " account number is not the same that was sent")
            .toEqual(expectedAccount.customerCode);
        expect(account.name, "The " + source + " description of the account " + expectedAccount.customerCode
            + " is not the same that was sent").toEqual(expectedAccount.name);
        expect(account.eventRefCode, "The " + source + " reference code of the account " + expectedAccount.customerCode
            + " is not the same that was sent").toEqual(expectedAccount.eventRefCode);
        expect(account.type, "The " + source + " type of the account " + expectedAccount.customerCode
            + " is not the same that was sent").toEqual(expectedAccount.type);
        expect(account.subType, "The " + source + " subtype of the account " + expectedAccount.customerCode
            + " is not the same that was sent").toEqual(expectedAccount.subType);
        expect(account.active, "The " + source + " active flag of the account " + expectedAccount.customerCode
            + " is not the same that was sent").toEqual(expectedAccount.active);
    }

    const validateRejectedRequest = async (response: APIResponse, expectedError: string) => {
        const responseBody = await response.text();
        expect(response.status(), "The request should be rejected by " + expectedError + ": " + responseBody)
            .toEqual(expectedStatusByError[expectedError]);
        const responseAccount: ChartOfAccountsDto = JSON.parse(responseBody);
        expect(responseAccount.error?.title, "The rejection reason is not the expected: " + responseBody)
            .toEqual(expectedError);
    }

    const validateAccountNotListed = async (chartOfAccountsResponse: APIResponse, accountCode: string) => {
        const listedAccounts = await getListedAccounts(chartOfAccountsResponse);
        expect(listedAccounts.map(account => account.customerCode),
            "The chart of account " + accountCode + " should not be listed").not.toContain(accountCode);
    }

    const getListedAccounts = async (chartOfAccountsResponse: APIResponse) => {
        expect(chartOfAccountsResponse.status(), "Chart of accounts should be requested successfully")
            .toEqual(HttpStatusCodes.success);
        const listedAccounts: ChartOfAccountsDto[] = await chartOfAccountsResponse.json();
        return listedAccounts
    }

    return {
        validateSuccessfulResponse,
        validateStoredAccount,
        validateRejectedRequest,
        validateAccountNotListed
    }
}
