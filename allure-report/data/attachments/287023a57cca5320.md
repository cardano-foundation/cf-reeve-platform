# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: tests/e2e/reprocess-transactions.feature.spec.js >> Users can reprocess transactions that are in pending status once the issue is solved, system checks >> Reprocess transaction in pending status by unknown account
- Location: .features-gen/tests/e2e/reprocess-transactions.feature.spec.js:27:7

# Error details

```
Error: expect(received).toEqual(expected) // deep equality

Expected: 200
Received: 400
```

# Test source

```ts
  10  | import {transactionValidator} from "../../validators/transactionValidator";
  11  | import {rejectTransactionBuilder} from "../../helpers/rejectTransactionBuilder";
  12  | import {RejectionCode} from "../../api/dtos/RejectTransactionDto";
  13  | 
  14  | Given(/^there is an imported transaction in pending status due to cost center mapping$/, async ({request, ctx}) => {
  15  |     ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
  16  | 
  17  |     const costCentersResponse = await (await reeveService(request)).getCostCenters(ctx.auth.authToken);
  18  |     expect(costCentersResponse.status()).toEqual(HttpStatusCodes.success);
  19  |     const costCenters: CostCenterDto[] = await costCentersResponse.json();
  20  | 
  21  |     const activeCostCenters = costCenters.filter(costCenter => costCenter.active);
  22  |     const randomIndex = Math.floor(Math.random() * activeCostCenters.length);
  23  |     const selectedCostCenter = activeCostCenters[randomIndex];
  24  |     ctx.costCenter.costCenterCode = selectedCostCenter.customerCode;
  25  |     ctx.costCenter.costCenterName = selectedCostCenter.name;
  26  | 
  27  |     const deactivateResponse = await (await reeveService(request)).updateCostCenter(ctx.auth.authToken, {
  28  |         customerCode: selectedCostCenter.customerCode,
  29  |         name: selectedCostCenter.name,
  30  |         active: false
  31  |     });
  32  |     expect(deactivateResponse.status()).toEqual(HttpStatusCodes.success);
  33  | 
  34  |     ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
  35  |         .createCSVTransactionPendingByCostCenter(ctx.transaction.transactionDataToImport, ctx.costCenter.costCenterCode);
  36  | 
  37  |     ctx.transaction.batchDetails = await (await commonTestActions(request)).importAndGetPendingBatch(
  38  |         ctx.auth.authToken, ctx.transaction.transactionCSVFile, ctx.transaction.transactionDataToImport[0].TxNumber
  39  |     );
  40  | });
  41  | 
  42  | Given(/^the issue with the cost center is adjusted in the system$/, async ({request, ctx}) => {
  43  |     const reactivateResponse = await (await reeveService(request)).updateCostCenter(ctx.auth.authToken, {
  44  |         customerCode: ctx.costCenter.costCenterCode,
  45  |         name: ctx.costCenter.costCenterName,
  46  |         active: true
  47  |     });
  48  |     expect(reactivateResponse.status()).toEqual(HttpStatusCodes.success);
  49  | });
  50  | 
  51  | When(/^the system process the reprocess request$/, async ({request, ctx}) => {
  52  |     const reprocessResponse = await (await reeveService(request)).reprocessBatch(ctx.auth.authToken, ctx.transaction.batchDetails.id);
  53  |     expect(reprocessResponse.status()).toEqual(HttpStatusCodes.success);
  54  | });
  55  | 
  56  | Then(/^the transaction should change the status to ready to approve$/, async ({request, ctx}) => {
  57  |     const reprocessedBatch = await (await reeveService(request)).getNewBatch(
  58  |         ctx.auth.authToken, BatchesStatusCodes.APPROVE, ctx.transaction.transactionDataToImport[0].TxNumber
  59  |     );
  60  |     await (await transactionValidator()).validateImportedTxWithStatus(
  61  |         ctx.transaction.transactionDataToImport, reprocessedBatch, BatchesStatusCodes.APPROVE
  62  |     );
  63  | });
  64  | 
  65  | Given(/^there is a ready to approve transaction$/, async ({request, ctx}) => {
  66  |     ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
  67  |     ctx.transaction.batchDetails = await (await commonTestActions(request)).importReadyToApproveTx(
  68  |         ctx.auth.authToken, ctx.transaction.transactionDataToImport
  69  |     );
  70  | });
  71  | 
  72  | Given(/^the transaction is reject due to issue with parent cost center mapping$/, async ({request, ctx}) => {
  73  |     const rejectPayload = await (await rejectTransactionBuilder(ctx.transaction.batchDetails))
  74  |         .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_COST_CENTER);
  75  | 
  76  |     const rejectResponse = await (await reeveService(request)).rejectTransaction(ctx.auth.authToken, rejectPayload);
  77  |     expect(rejectResponse.status()).toEqual(HttpStatusCodes.success);
  78  | 
  79  |     ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
  80  |         ctx.auth.authToken, BatchesStatusCodes.PENDING, ctx.transaction.transactionDataToImport[0].TxNumber
  81  |     );
  82  | });
  83  | 
  84  | Given(/^there is an imported transaction in pending status due to unknown account$/, async ({request, ctx}) => {
  85  |     ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
  86  | 
  87  |     ctx.transaction.transactionCSVFile = await (await transactionsBuilder(request, ctx.auth.authToken))
  88  |         .createReadyToApproveTransaction(ctx.transaction.transactionDataToImport);
  89  | 
  90  |     const debitCode = ctx.transaction.transactionDataToImport[0].DebitCode;
  91  |     const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(ctx.auth.authToken, debitCode);
  92  |     expect(chartOfAccountsResponse.status()).toEqual(HttpStatusCodes.success);
  93  |     const chartOfAccounts: ChartOfAccountsDto[] = await chartOfAccountsResponse.json();
  94  |     const selectedCoA = chartOfAccounts[0];
  95  | 
  96  |     ctx.chartOfAccount.updateDto = {
  97  |         customerCode: selectedCoA.customerCode,
  98  |         eventRefCode: selectedCoA.eventRefCode,
  99  |         name: selectedCoA.name,
  100 |         subType: selectedCoA.subType,
  101 |         type: selectedCoA.type,
  102 |         currency: selectedCoA.currency,
  103 |         counterParty: selectedCoA.counterParty,
  104 |         parentCustomerCode: selectedCoA.parentCustomerCode,
  105 |         openingBalance: selectedCoA.openingBalance,
  106 |         active: false
  107 |     };
  108 | 
  109 |     const deactivateResponse = await (await reeveService(request)).updateChartOfAccounts(ctx.auth.authToken, ctx.chartOfAccount.updateDto);
> 110 |     expect(deactivateResponse.status()).toEqual(HttpStatusCodes.success);
      |                                         ^ Error: expect(received).toEqual(expected) // deep equality
  111 | 
  112 |     ctx.transaction.batchDetails = await (await commonTestActions(request)).importAndGetPendingBatch(
  113 |         ctx.auth.authToken, ctx.transaction.transactionCSVFile, ctx.transaction.transactionDataToImport[0].TxNumber
  114 |     );
  115 | });
  116 | 
  117 | Given(/^the issue with the account is adjusted in the system$/, async ({request, ctx}) => {
  118 |     const reactivateResponse = await (await reeveService(request)).updateChartOfAccounts(ctx.auth.authToken, {
  119 |         ...ctx.chartOfAccount.updateDto,
  120 |         active: true
  121 |     });
  122 |     expect(reactivateResponse.status()).toEqual(HttpStatusCodes.success);
  123 | });
  124 | 
  125 | Given(/^the transaction is reject due to issue with project code mapping$/, async ({request, ctx}) => {
  126 |     const rejectPayload = await (await rejectTransactionBuilder(ctx.transaction.batchDetails))
  127 |         .createRejectTransactionPayloadWithReason(RejectionCode.REVIEW_PARENT_PROJECT_CODE);
  128 | 
  129 |     const rejectResponse = await (await reeveService(request)).rejectTransaction(ctx.auth.authToken, rejectPayload);
  130 |     expect(rejectResponse.status()).toEqual(HttpStatusCodes.success);
  131 | 
  132 |     ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
  133 |         ctx.auth.authToken, BatchesStatusCodes.PENDING, ctx.transaction.transactionDataToImport[0].TxNumber
  134 |     );
  135 | });
  136 | 
  137 | Given(/^there is an approved transaction$/, async ({request, ctx}) => {
  138 |     ctx.auth.authToken = await (await commonTestActions(request)).loginUser();
  139 |     const approveReadyBatch = await (await commonTestActions(request)).importReadyToApproveTx(
  140 |         ctx.auth.authToken, ctx.transaction.transactionDataToImport
  141 |     );
  142 | 
  143 |     const approveResponse = await (await reeveService(request)).approveTransaction(ctx.auth.authToken, approveReadyBatch);
  144 |     expect(approveResponse.status()).toEqual(HttpStatusCodes.success);
  145 | 
  146 |     ctx.transaction.batchDetails = await (await reeveService(request)).getNewBatch(
  147 |         ctx.auth.authToken, BatchesStatusCodes.PUBLISH, ctx.transaction.transactionDataToImport[0].TxNumber
  148 |     );
  149 | });
```