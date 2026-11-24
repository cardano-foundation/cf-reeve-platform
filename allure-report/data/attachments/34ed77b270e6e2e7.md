# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: tests/e2e/login.feature.spec.js >> Login and authentication process tests >> Manager user can login with its credentials
- Location: .features-gen/tests/e2e/login.feature.spec.js:6:7

# Error details

```
Error: expect(received).toEqual(expected) // deep equality

Expected: 200
Received: 401
```

# Test source

```ts
  1  | import {APIResponse, expect} from '@playwright/test';
  2  | import {faker} from "@faker-js/faker";
  3  | import {Given, When, Then} from "../../utils/playwright-bdd-fixtures";
  4  | import {reeveService} from "../../api/reeve-api/reeve.service";
  5  | import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
  6  | 
  7  | let userName: string;
  8  | let password: string;
  9  | let loginResponse: APIResponse;
  10 | Given(/^Manager user wants to login into Reeve$/, async () => {
  11 |     userName = process.env.MANAGER_USER as string;
  12 |     password = process.env.MANAGER_PASSWORD as string;
  13 | });
  14 | When(/^system get the login request$/, async ({request}) => {
  15 |     loginResponse = await (await reeveService(request)).loginToReeve(userName, password)
  16 | });
  17 | Then(/^system should return success login response with authorization token$/, async () => {
> 18 |     expect(loginResponse.status()).toEqual(HttpStatusCodes.success)
     |                                    ^ Error: expect(received).toEqual(expected) // deep equality
  19 |     const authToken = (await loginResponse.json()).access_token
  20 |     const tokenType = (await loginResponse.json()).token_type
  21 |     expect(authToken).toBeDefined()
  22 |     expect(tokenType).toContain("Bearer")
  23 | });
  24 | Given(/^Manager user wants to login into Reeve with wrong credentials$/, async () => {
  25 |     userName = faker.internet.userAgent()
  26 |     password = faker.string.sample()
  27 | });
  28 | Then(/^system should reject access$/, async () => {
  29 |     expect(loginResponse.status()).toEqual(HttpStatusCodes.BadRequest)
  30 |     expect((await loginResponse.json()).error).toEqual("invalid_grant")
  31 | });
  32 | 
```