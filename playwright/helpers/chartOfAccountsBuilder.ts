import {APIRequestContext, expect} from "@playwright/test";
import {faker} from "@faker-js/faker";
import {reeveService} from "../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../api/api-helpers/http-status-codes";
import {EventCodesDto} from "../api/dtos/eventCodesDto";
import {ChartOfAccountsDto, ChartTypeDto, UpdateChartOfAccountsDto} from "../api/dtos/chartOfAccountsDto";

export async function chartOfAccountsBuilder(request: APIRequestContext, authToken: string) {

    /**
     * Create the data for a new chart of account with only the required fields
     * customerCode Random 12 digits number, it doesn't exist in the system
     * name Random description with TEST prefix
     * type and subType organization chart types requested through API
     * eventRefCode debit reference code of an organization event code that has credit accounts,
     * so the new account can be used to import transactions
     */
    const createChartOfAccountData = async () => {
        const chartType = await getRandomChartType();
        const chartOfAccount: UpdateChartOfAccountsDto = {
            customerCode: faker.string.numeric({length: 12, allowLeadingZeros: false}),
            eventRefCode: await getUsableReferenceCode(),
            name: getRandomDescription(),
            type: chartType.id,
            subType: faker.helpers.arrayElement(chartType.subType).id,
            currency: "",
            counterParty: "",
            parentCustomerCode: "",
            active: true
        }
        return chartOfAccount
    }

    const createChartOfAccountDataWithNumber = async (customerCode: string) => {
        const chartOfAccount = await createChartOfAccountData();
        chartOfAccount.customerCode = customerCode;
        return chartOfAccount
    }

    const createChartOfAccountDataWithNewDescription = (chartOfAccount: UpdateChartOfAccountsDto) => {
        return {...chartOfAccount, name: getRandomDescription()}
    }

    const createDeactivatedChartOfAccountData = (chartOfAccount: UpdateChartOfAccountsDto) => {
        return {...chartOfAccount, active: false}
    }

    const getRandomDescription = () => "TEST " + faker.finance.accountName() + " " +
        Math.random().toString(36).substring(2, 2 + 8);

    const getRandomChartType = async () => {
        const chartTypesResponse = await (await reeveService(request)).getChartTypes(authToken);
        expect(chartTypesResponse.status(), "Chart types should be requested successfully")
            .toEqual(HttpStatusCodes.success);
        const chartTypes: ChartTypeDto[] = await chartTypesResponse.json();
        const chartTypesWithSubTypes = chartTypes.filter(chartType => chartType.subType?.length > 0);
        if (chartTypesWithSubTypes.length == 0) throw new Error("No chart type with subtypes found in the organization");
        return faker.helpers.arrayElement(chartTypesWithSubTypes)
    }

    /**
     * Get the debit reference code of an active event code
     * whose credit reference code has at least two active accounts,
     * so the transaction items can use different debit/credit pairs with the new account
     */
    const getUsableReferenceCode = async () => {
        const eventCodesResponse = await (await reeveService(request)).getEventCodes(authToken);
        expect(eventCodesResponse.status(), "Event codes should be requested successfully")
            .toEqual(HttpStatusCodes.success);
        const eventCodes: EventCodesDto[] = await eventCodesResponse.json();
        const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(authToken);
        expect(chartOfAccountsResponse.status(), "Chart of accounts should be requested successfully")
            .toEqual(HttpStatusCodes.success);
        const chartOfAccounts: ChartOfAccountsDto[] = await chartOfAccountsResponse.json();
        const usableEventCodes = eventCodes.filter(eventCode => eventCode.active
            && eventCode.debitReferenceCode !== eventCode.creditReferenceCode
            && chartOfAccounts.filter(account => account.eventRefCode === eventCode.creditReferenceCode).length >= 2);
        if (usableEventCodes.length == 0) throw new Error("No event code with at least two credit accounts found in the organization");
        return faker.helpers.arrayElement(usableEventCodes).debitReferenceCode
    }

    return {
        createChartOfAccountData,
        createChartOfAccountDataWithNumber,
        createChartOfAccountDataWithNewDescription,
        createDeactivatedChartOfAccountData
    }
}
