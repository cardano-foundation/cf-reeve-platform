import {expect} from '@playwright/test';
import {Given, When, Then} from "../../utils/playwright-bdd-fixtures";
import {reeveService} from "../../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../../api/api-helpers/http-status-codes";
import {BatchesStatusCodes} from "../../api/api-helpers/batches-status-codes";
import {commonTestActions} from "../../helpers/common-test-actions";
import {transactionsBuilder} from "../../helpers/transactionsBuilder";
import {CostCenterDto} from "../../api/dtos/costCenterDto";
import {ChartOfAccountsDto} from "../../api/dtos/chartOfAccountsDto";
import {transactionValidator} from "../../validators/transactionValidator";
import {rejectTransactionBuilder} from "../../helpers/rejectTransactionBuilder";
import {RejectionCode} from "../../api/dtos/RejectTransactionDto";

Given(/^there is an imported transaction in pending status due to cost center mapping$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();

    const costCentersResponse = await (await reeveService(request)).getCostCenters(ctx.auth.authToken);
    expect(costCentersResponse.status()).toEqual(HttpStatusCodes.success);
    const costCenters: CostCenterDto[] = await costCentersResponse.json();

    const activeCostCenters = costCenters.filter(costCenter => costCenter.active);
    const randomIndex = Math.floor(Math.random() * activeCostCenters.length);
    const selectedCostCenter = activeCostCenters[randomIndex];
    ctx.costCenter.costCenterCode = selectedCostCenter.customerCode;
    ctx.costCenter.costCenterName = selectedCostCenter.name;

    const deactivateResponse = await (await reeveService(request)).updateCostCenter(ctx.auth.authToken, {
        customerCode: selectedCostCenter.customerCode,
        name: selectedCostCenter.name,
        active: false
    });
    expect(deactivateResponse.status()).toEqual(HttpStatusCodes.success);

    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createCSVTransactionPendingByCostCenter(ctx.transaction.transactionDataToImport, ctx.costCenter.costCenterCode);

    ctx.transaction.batchDetails = await (await commonTestActions(request)).importAndGetPendingBatch(
        ctx.auth.authToken, ctx.transaction.transactionCSVFile, ctx.transaction.transactionDataToImport[0].TxNumber
    );
});

Given(/^the issue with the cost center is adjusted in the system$/, async ({request, ctx}) => {
    const reactivateResponse = await (await reeveService(request)).updateCostCenter(ctx.auth.authToken, {
        customerCode: ctx.costCenter.costCenterCode,
        name: ctx.costCenter.costCenterName,
        active: true
    });
    expect(reactivateResponse.status()).toEqual(HttpStatusCodes.success);
});

When(/^the system process the reprocess request$/, async ({request, ctx}) => {
    const reprocessResponse = await (await reeveService(request)).reprocessBatch(ctx.auth.authToken, ctx.transaction.batchDetails.id);
    expect(reprocessResponse.status()).toEqual(HttpStatusCodes.success);
});

Then(/^the transaction should change the status to ready to approve$/, async ({request, ctx}) => {
    const reprocessedBatch = await (await reeveService(request)).getNewBatch(
        ctx.auth.authToken, BatchesStatusCodes.APPROVE, ctx.transaction.transactionDataToImport[0].TxNumber
    );
    await (await transactionValidator()).validateImportedTxWithStatus(
        ctx.transaction.transactionDataToImport, reprocessedBatch, BatchesStatusCodes.APPROVE
    );
});

Given(/^there is a ready to approve transaction$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
    ctx.transaction.batchDetails = await (await commonTestActions(request)).importReadyToApproveTx(
        ctx.auth.authToken, ctx.transaction.transactionDataToImport
    );
});

Given(/^the transaction is reject due to issue with parent cost center mapping$/, async ({request, ctx}) => {
    const rejectPayload = await (await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_COST_CENTER);

    const rejectResponse = await (await reeveService(request)).rejectTransaction(ctx.auth.authToken, rejectPayload);
    expect(rejectResponse.status()).toEqual(HttpStatusCodes.success);

    ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
        ctx.auth.authToken, BatchesStatusCodes.PENDING, ctx.transaction.transactionDataToImport[0].TxNumber
    );
});

Given(/^there is an imported transaction in pending status due to unknown account$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();

    ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
        .createReadyToApproveTransaction(ctx.transaction.transactionDataToImport);

    const debitCode = ctx.transaction.transactionDataToImport[0].DebitCode;
    const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken, debitCode);
    expect(chartOfAccountsResponse.status()).toEqual(HttpStatusCodes.success);
    const chartOfAccounts: ChartOfAccountsDto[] = await chartOfAccountsResponse.json();
    const selectedCoA = chartOfAccounts[0];

    ctx.chartOfAccount.updateDto = {
        customerCode: selectedCoA.customerCode,
        eventRefCode: selectedCoA.eventRefCode,
        name: selectedCoA.name,
        subType: selectedCoA.subType,
        type: selectedCoA.type,
        currency: selectedCoA.currency,
        counterParty: selectedCoA.counterParty,
        parentCustomerCode: selectedCoA.parentCustomerCode,
        openingBalance: selectedCoA.openingBalance,
        active: false
    };

    const deactivateResponse = await (await reeveService(request)).updateChartOfAccounts(ctx.auth.authToken, ctx.chartOfAccount.updateDto);
    expect(deactivateResponse.status()).toEqual(HttpStatusCodes.success);

    ctx.transaction.batchDetails = await (await commonTestActions(request)).importAndGetPendingBatch(
        ctx.auth.authToken, ctx.transaction.transactionCSVFile, ctx.transaction.transactionDataToImport[0].TxNumber
    );
});

Given(/^the issue with the account is adjusted in the system$/, async ({request, ctx}) => {
    const reactivateResponse = await (await reeveService(request)).updateChartOfAccounts(ctx.auth.authToken, {
        ...ctx.chartOfAccount.updateDto,
        active: true
    });
    expect(reactivateResponse.status()).toEqual(HttpStatusCodes.success);
});

Given(/^the transaction is reject due to issue with project code mapping$/, async ({request, ctx}) => {
    const rejectPayload = await (await rejectTransactionBuilder(ctx.transaction.batchDetails))
        .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_PROJECT_CODE);

    const rejectResponse = await (await reeveService(request)).rejectTransaction(ctx.auth.authToken, rejectPayload);
    expect(rejectResponse.status()).toEqual(HttpStatusCodes.success);

    ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
        ctx.auth.authToken, BatchesStatusCodes.PENDING, ctx.transaction.transactionDataToImport[0].TxNumber
    );
});

Given(/^there is an approved transaction$/, async ({request, ctx}) => {
    ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
    const approveReadyBatch = await (await commonTestActions(request)).importReadyToApproveTx(
        ctx.auth.authToken, ctx.transaction.transactionDataToImport
    );

    const approveResponse = await (await reeveService(request)).approveTransaction(ctx.auth.authToken, approveReadyBatch);
    expect(approveResponse.status()).toEqual(HttpStatusCodes.success);

    ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
        ctx.auth.authToken, BatchesStatusCodes.PUBLISH, ctx.transaction.transactionDataToImport[0].TxNumber
    );
});