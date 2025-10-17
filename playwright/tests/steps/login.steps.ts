import {APIResponse, expect} from '@playwright/test';
import {faker} from "@faker-js/faker";
import {createBdd} from 'playwright-bdd';
import {reeveApi} from "../../api/reeve-api/reeve.api";
import {reeveService} from "../../api/reeve-api/reeve.service";
import {log} from "../../utils/logger";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";

const {Given, When, Then} = createBdd();

let userName: string;
let password: string;
let loginResponse: APIResponse;
Given(/^Manager user wants to login into Reeve$/, async ({page}) => {
    userName = process.env.MANAGER_USER as string;
    password = process.env.MANAGER_PASSWORD as string;
});
When(/^system get the login request$/, async ({request}) => {
    loginResponse = await (await reeveService(request)).loginToReeve(userName, password)
});
Then(/^system should return success login response with authorization token$/, async ({page}) => {
    expect(loginResponse.status()).toEqual(HttpStatusCodes.success)
    const authToken = (await loginResponse.json()).access_token
    const tokenType = (await loginResponse.json()).token_type
    expect(authToken).toBeDefined()
    expect(tokenType).toContain("Bearer")
});
Given(/^Manager user wants to login into Reeve with wrong credentials$/, async () => {
    userName = faker.internet.userAgent()
    password = faker.string.sample()
});
Then(/^system should reject access$/, async () => {
    expect(loginResponse.status()).toEqual(HttpStatusCodes.Unauthorized)
});