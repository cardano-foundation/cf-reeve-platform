# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: tests/e2e/chart-of-accounts.feature.spec.js >> Manager can add and maintain the organization chart of accounts, new active accounts >> New chart of account is valid for imported transactions
- Location: .features-gen/tests/e2e/chart-of-accounts.feature.spec.js:41:7

# Error details

```
Error: The new Batch was not created: 

expect(received).not.toBeNull()

Received: null

Call Log:
- Test timeout of 120000ms exceeded
```

# Test source

```ts
  1   | import {APIRequestContext, expect} from "@playwright/test";
  2   | import {reeveApi} from "./reeve.api";
  3   | import {Batch, BatchData} from "../dtos/batchsDto";
  4   | import {BatchesStatusCodes} from "../api-helpers/batches-status-codes";
  5   | import {BatchResponse} from "../dtos/batchDto";
  6   | import {log} from "../../utils/logger";
  7   | import {RejectTransactionDto} from "../dtos/RejectTransactionDto";
  8   | import {UpdateCostCenterDto} from "../dtos/costCenterDto";
  9   | import {UpdateChartOfAccountsDto} from "../dtos/chartOfAccountsDto";
  10  | 
  11  | let managerUser = process.env.MANAGER_USER as string;
  12  | let managerPassword = process.env.MANAGER_PASSWORD as string;
  13  | let organizationId = process.env.ORGANIZATION_ID as string;
  14  | export async function reeveService(request: APIRequestContext) {
  15  |     const loginToReeve = async (userName: string, password: string) => {
  16  |         return await reeveApi(request).loginReeve(userName, password);
  17  |     };
  18  | 
  19  |     const loginManager = async () => {
  20  |         return await reeveApi(request).loginReeve(managerUser, managerPassword);
  21  |     }
  22  | 
  23  |     const getTransactionTypes = async (authToken: string) => {
  24  |         return await reeveApi(request).transactionTypes(organizationId, authToken);
  25  |     }
  26  | 
  27  |     const getEventCodes = async (authToken: string) => {
  28  |         return await reeveApi(request).eventCodes(organizationId, authToken);
  29  |     }
  30  | 
  31  |     const getChartOfAccounts = async (authToken: string, customerCode?: string) => {
  32  |         return await reeveApi(request).chartOfAccounts(organizationId, authToken, customerCode);
  33  |     }
  34  | 
  35  |     const getCostCenters = async (authToken: string) => {
  36  |         return await reeveApi(request).getCostCenters(organizationId, authToken);
  37  |     }
  38  | 
  39  |     const updateCostCenter = async (authToken: string, costCenter: UpdateCostCenterDto) => {
  40  |         return await reeveApi(request).updateCostCenter(organizationId, authToken, costCenter);
  41  |     }
  42  | 
  43  |     const updateChartOfAccounts = async (authToken: string, chartOfAccount: UpdateChartOfAccountsDto) => {
  44  |         return await reeveApi(request).updateChartOfAccounts(organizationId, authToken, chartOfAccount);
  45  |     }
  46  | 
  47  |     const createChartOfAccounts = async (authToken: string, chartOfAccount: UpdateChartOfAccountsDto) => {
  48  |         return await reeveApi(request).createChartOfAccounts(organizationId, authToken, chartOfAccount);
  49  |     }
  50  | 
  51  |     const getChartTypes = async (authToken: string) => {
  52  |         return await reeveApi(request).chartTypes(organizationId, authToken);
  53  |     }
  54  | 
  55  |     const validateTransactionCsvFile = async (authToken: string, transactionFile: string) => {
  56  |         return await reeveApi(request).validateTransactionCsvFile(organizationId, authToken, transactionFile);
  57  |     }
  58  | 
  59  |     const importTransactionCsvFile = async (authToken: string, transactionFile: string) => {
  60  |         return await reeveApi(request).importTransactionCsvFile(organizationId,authToken, transactionFile)
  61  |     }
  62  | 
  63  |     const getBatchesByStatus = async (authToken: string, status: string) => {
  64  |         return await reeveApi(request).batchesByStatus(organizationId, authToken, status);
  65  |     }
  66  | 
  67  |     const getBatchById = async (authToken: string, batchId: string) => {
  68  |         return await reeveApi(request).batchById(organizationId, authToken, batchId)
  69  |     }
  70  | 
  71  |     const findBatch = async (
  72  |         batchIds: string[],
  73  |         authToken: string,
  74  |         visitedBatchIds: Set<string>,
  75  |         matcher: (batch: BatchResponse) => boolean
  76  |     ) => {
  77  |         for (const batchId of batchIds) {
  78  |             const batchDetailsResponse: BatchResponse = await (await getBatchById(authToken, batchId)).json();
  79  |             visitedBatchIds.add(batchId);
  80  |             if (matcher(batchDetailsResponse)) {
  81  |                 return batchDetailsResponse;
  82  |             }
  83  |         }
  84  |         return null;
  85  |     }
  86  | 
  87  |     const pollForNewBatch = async (
  88  |         authToken: string,
  89  |         status: string,
  90  |         matcher: (batch: BatchResponse) => boolean
  91  |     ) => {
  92  |         let matchedBatch: BatchResponse;
  93  |         const visitedBatchIds = new Set<string>();
> 94  |         await expect.poll(async () => {
      |         ^ Error: The new Batch was not created: 
  95  |             const batchesResponse = await getBatchesByStatus(authToken, status);
  96  |             const batchesAfterImport: BatchData = await batchesResponse.json();
  97  |             const allBatchIds = batchesAfterImport.batchs.map(batch => batch.id);
  98  |             const newBatchIds = allBatchIds.filter(id => !visitedBatchIds.has(id));
  99  |             matchedBatch = await findBatch(newBatchIds, authToken, visitedBatchIds, matcher);
  100 |             return matchedBatch;
  101 |         }, {
  102 |             message: "The new Batch was not created: ",
  103 |             intervals: [1_000, 2_000, 10_000],
  104 |             timeout: 280_000
  105 |         }).not.toBeNull();
  106 |         return matchedBatch;
  107 |     }
  108 | 
  109 |     const getNewBatch = async (authToken: string, status: string, txNumber: string) =>
  110 |         pollForNewBatch(authToken, status, batch => batch.transactions[0].internalTransactionNumber == txNumber);
  111 | 
  112 |     const getNewBatchByDocumentNumber = async (authToken: string, status: string, documentNumber: string) =>
  113 |         pollForNewBatch(authToken, status, batch => batch.transactions[0].items[0].documentNum == documentNumber);
  114 |     const reprocessBatch = async (authToken: string, batchId: string) => {
  115 |         return await reeveApi(request).reprocessBatch(organizationId, authToken, batchId);
  116 |     }
  117 | 
  118 |     const approveTransaction = async (authToken: string, batchDetails: BatchResponse) => {
  119 |         return await reeveApi(request).approveTransaction(authToken, {
  120 |             organisationId: batchDetails.organisationId,
  121 |             transactionIds: batchDetails.transactions.map(tx => ({ id: tx.id }))
  122 |         });
  123 |     }
  124 | 
  125 |     const rejectTransaction = async (authToken: string, transactionToReject: RejectTransactionDto) => {
  126 |         return await reeveApi(request).rejectTransaction(authToken, transactionToReject)
  127 |     }
  128 |     const getTransactionById = async (authToken: string, transactionId: string) => {
  129 |         return await reeveApi(request).getTransactionById(organizationId, authToken, transactionId)
  130 |     }
  131 |     return {
  132 |         loginToReeve,
  133 |         loginManager,
  134 |         getTransactionTypes,
  135 |         getEventCodes,
  136 |         getChartOfAccounts,
  137 |         getCostCenters,
  138 |         updateCostCenter,
  139 |         updateChartOfAccounts,
  140 |         createChartOfAccounts,
  141 |         getChartTypes,
  142 |         validateTransactionCsvFile,
  143 |         importTransactionCsvFile,
  144 |         getBatchesByStatus,
  145 |         getNewBatch,
  146 |         getBatchById,
  147 |         getNewBatchByDocumentNumber,
  148 |         reprocessBatch,
  149 |         approveTransaction,
  150 |         rejectTransaction,
  151 |         getTransactionById
  152 |     };
  153 | 
  154 | }
```