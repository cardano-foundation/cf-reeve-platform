import {APIResponse, expect} from '@playwright/test';
import {Given, When, Then} from "../../utils/playwright-bdd-fixtures";
import {reeveService} from "../../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
import {commonTestActions} from "../../helpers/common-test-actions";
import {rejectTransactionBuilder} from "../../helpers/rejectTransactionBuilder";
import {RejectionCode} from "../../api/dtos/RejectTransactionDto";
import {log} from "../../utils/logger";
import {rejectTransactionValidator} from "../../validators/rejectTransactionValidator";

Given(/^user import ready to approve transaction to the system$/, async ({request , ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser()
    ctx.transaction.batchDetails = await (await commonTestActions(request)).importReadyToApproveTx(ctx.auth.authToken,
        ctx.transaction.transactionDataToImport)
});
Given(/^And the user wants to rejects the transaction by incorrect cost center$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.INCORRECT_COST_CENTER)
});
When(/^the system get the rejection request$/, async ({request, ctx}) => {
    const rejectTxApiResponse = await (await reeveService(request))
        .rejectTransaction(ctx.auth.authToken, ctx.transaction.rejectTransactionData)
    expect(rejectTxApiResponse.status()).toEqual(HttpStatusCodes.success)
    ctx.transaction.rejectTransactionResponse = await rejectTxApiResponse.json()
});
Then(/^the transaction should be now in invalid status by "([^"]*)" reason$/, async ({request, ctx}, reason) => {
    const transactionApiResponse = await (await reeveService(request))
        .getTransactionById(ctx.auth.authToken, ctx.transaction.rejectTransactionResponse.transactionId)
    expect(transactionApiResponse.status()).toEqual(HttpStatusCodes.success)
    const transactionDetails = await transactionApiResponse.json()
    await (await rejectTransactionValidator()).validateTransactionWithInvalidStatus(transactionDetails, reason)
});
Given(/^And the user wants to rejects the transaction by incorrect amount$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.INCORRECT_AMOUNT)
});
Given(/^And the user wants to rejects the transaction by incorrect vat code$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.INCORRECT_VAT_CODE)
});
Given(/^And the user wants to rejects the transaction by incorrect currency$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.INCORRECT_CURRENCY)
});
Given(/^And the user wants to rejects the transaction by incorrect project$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.INCORRECT_PROJECT)
});
Given(/^And the user wants to rejects the transaction by review parent cost center$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_COST_CENTER)
});
Then(/^the transaction should be now in pending status by "([^"]*)" reason$/, async ({request, ctx},reason) => {
    const transactionApiResponse = await (await reeveService(request))
        .getTransactionById(ctx.auth.authToken, ctx.transaction.rejectTransactionResponse.transactionId)
    expect(transactionApiResponse.status()).toEqual(HttpStatusCodes.success)
    const transactionDetails = await transactionApiResponse.json()
    await (await rejectTransactionValidator()).validateTransactionWithPendingStatus(transactionDetails, reason)
});
Given(/^And the user wants to rejects the transaction by review parent project code$/, async ({request, ctx}) => {
    ctx.transaction.rejectTransactionData = await ( await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_PROJECT_CODE)
});