import {faker} from "@faker-js/faker";
import {saveCSV} from "../utils/csvFileGenerator";
import {TxCSVHeader} from "./transactionCSVProperties";
import {log} from "../utils/logger";
import {APIRequestContext, expect} from "@playwright/test";
import {reeveService} from "../api/reeve-api/reeve.service";
import {HttpStatusCodes} from "../api/api-helpers/http-status-codes";
import {TransactionTypeDto} from "../api/dtos/transactionTypesDto";
import {getDateInThePast} from "../utils/dateGenerator";
import {TransactionItemCsvDto} from "../api/dtos/transactionItemCsvDto";
import {EventCodesDto, ReferenceCodePair} from "../api/dtos/eventCodesDto";
import {
    AccountCodeAndNamePair,
    AccountRefCodePair,
    DebitAndCreditAccounts,
    TransactionItemAccounts
} from "../api/dtos/chartOfAccountsDto";
import {TransactionPendingInvalidStatus} from "./transaction-pending-invalid-status";

export async function transactionsBuilder(request: APIRequestContext, authToken: string) {
    const createCSVTransactionReadyToApprove = async (transactionDataToImport: TransactionItemCsvDto[]) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createValidTransactionData(transactionDataToImport)
        const fileName = "Approve-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName);
    }
    const createCSVTransactionReadyToApproveWithDebitAccount = async (transactionDataToImport: TransactionItemCsvDto[],
                                                                      debitAccount: AccountRefCodePair) => {
        const columns = await getTransactionCSVHeaders();
        const debitAndCreditAccounts = await getCreditAccountsForDebitAccount(debitAccount);
        const rows = await createValidTransactionData(transactionDataToImport, debitAndCreditAccounts);
        const fileName = "Approve-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName);
    }
    const createCSVTransactionPending = async (transactionDataToImport: TransactionItemCsvDto[], pendingReason: string) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createPendingTransactionData(transactionDataToImport, pendingReason);
        const fileName = "Pending-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName)
    }
    const createCSVTransactionPendingByCostCenter = async (transactionDataToImport: TransactionItemCsvDto[], costCenterCode: string) => {
        const columns = getTransactionCSVHeaders();
        const transactionCommonData = await getTransactionCommonData();
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        const eventCodes = await getEventCodes();
        const debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        const [debitItemAccounts, creditItemAccounts] = getTransactionItemsAccounts(debitAndCreditAccounts);
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem, true, debitItemAccounts);
        debitTxItem.TxCostCenter = costCenterCode;
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem, false, creditItemAccounts);
        transactionDataToImport.push(debitTxItem);
        transactionDataToImport.push(creditTxItem);
        const rows: string[][] = [];
        rows.push(Object.values(debitTxItem));
        rows.push(Object.values(creditTxItem));
        const fileName = "Pending-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName);
    }

    const createCSVTransactionInvalid = async (transactionDataToImport: TransactionItemCsvDto[], invalidReason: string) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createInvalidTransactionData(transactionDataToImport, invalidReason);
        const fileName = "Invalid-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName)

    }
    const getTransactionCSVHeaders = () => Object.values(TxCSVHeader);

    /**
     * Create a transaction with just two transactions items,
     * txNumber Random short hash
     * documentName Random short hash
     * txType organization transaction type requested through API
     * debitTxItem accounts are requested through API in base of organization event codes
     * creditTxItem accounts are requested through API in base of organization event codes
     * debitAndCreditAccounts optional accounts to use instead of the requested ones
     */
    const createValidTransactionData = async (transactionDataToImport: TransactionItemCsvDto[],
                                              debitAndCreditAccounts?: DebitAndCreditAccounts) => {
        const transactionCommonData = await getTransactionCommonData()
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        if (!debitAndCreditAccounts) {
            const eventCodes = await getEventCodes();
            debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        }
        const [debitItemAccounts, creditItemAccounts] = getTransactionItemsAccounts(debitAndCreditAccounts);
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            true, debitItemAccounts);
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            false, creditItemAccounts);
        transactionDataToImport.push(debitTxItem);
        transactionDataToImport.push(creditTxItem);
        const rows: string[][] = [];
        rows.push(Object.values(debitTxItem));
        rows.push(Object.values(creditTxItem))
        return rows
    }
    const createPendingTransactionData = async (transactionDataToImport: TransactionItemCsvDto[], pendingReason: string) => {
        const transactionCommonData = await getTransactionCommonData()
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        const eventCodes = await getEventCodes();
        const debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        const [debitItemAccounts, creditItemAccounts] = getTransactionItemsAccounts(debitAndCreditAccounts);
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            true, debitItemAccounts)
        await setPendingReason(debitTxItem, pendingReason);
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            false, creditItemAccounts);
        transactionDataToImport.push(debitTxItem);
        transactionDataToImport.push(creditTxItem);
        const rows: string[][] = [];
        rows.push(Object.values(debitTxItem));
        rows.push(Object.values(creditTxItem))
        return rows
    }
    const createInvalidTransactionData = async (transactionDataToImport: TransactionItemCsvDto[], invalidReason: string) => {
        const transactionCommonData = await getTransactionCommonData()
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        const eventCodes = await getEventCodes();
        const debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        const [debitItemAccounts, creditItemAccounts] = getTransactionItemsAccounts(debitAndCreditAccounts);
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            true, debitItemAccounts)
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            false, creditItemAccounts);
        await setInvalidReason(debitTxItem, creditTxItem,invalidReason);
        transactionDataToImport.push(debitTxItem);
        transactionDataToImport.push(creditTxItem);
        const rows: string[][] = [];
        rows.push(Object.values(debitTxItem));
        rows.push(Object.values(creditTxItem))
        return rows
    }
    const getTransactionCommonData = async () => {
        const txNumber = "TEST-" + Math.random().toString(36).substring(2, 2 + 8);
        const txDate = getDateInThePast(2, true);
        const txType = await getTransactionType();
        const documentName = "TEST-" + Math.random().toString(36).substring(2, 2 + 8);
        const transactionItemCommonData: TransactionItemCsvDto = {
            TxNumber: txNumber,
            TxDate: txDate,
            TxType: txType,
            DocumentName: documentName
        }
        return transactionItemCommonData
    }
    const setPendingReason = async (transactionItem: TransactionItemCsvDto, pendingReason: string) => {
        if(pendingReason == TransactionPendingInvalidStatus.COST_CENTER_DATA_NOT_FOUND){
            transactionItem.TxCostCenter = Math.random().toString(36).substring(2, 2 + 8);
        }
        if(pendingReason == TransactionPendingInvalidStatus.VAT_DATA_NOT_FOUND){
            transactionItem.VatCode = Math.random().toString(36).substring(2, 2 + 8);
        }
        if(pendingReason == TransactionPendingInvalidStatus.CHART_OF_ACCOUNT_NOT_FOUND){
            transactionItem.DebitCode = Math.random().toString(36).substring(2, 2 + 8);
        }
    }
    const setInvalidReason = async (debitTransactionItem: TransactionItemCsvDto, creditTransactionItem: TransactionItemCsvDto,
                                    invalidReason: string) => {
        if(invalidReason == TransactionPendingInvalidStatus.UNBALANCED_TRANSACTION){
            debitTransactionItem.AmountLcyDebit = (Number(debitTransactionItem.AmountLcyDebit) + 1000).toString();
            debitTransactionItem.AmountFcyDebit = (Number(debitTransactionItem.AmountFcyDebit) + 1000).toString();
        }
        if(invalidReason == TransactionPendingInvalidStatus.TX_INTERNAL_NUMBER_MUST_BE_PRESENT){
            debitTransactionItem.TxNumber = "";
            creditTransactionItem.TxNumber= "";
        }
        if(invalidReason == TransactionPendingInvalidStatus.ACCOUNT_CODE_DEBIT_IS_EMPTY){
            debitTransactionItem.DebitCode = "";
        }
    }
    const getTransactionType = async () => {
        const transactionTypeResponse = await (await reeveService(request))
            .getTransactionTypes(authToken);
        expect(transactionTypeResponse.status()).toEqual(HttpStatusCodes.success);
        const transactionTypes: TransactionTypeDto[] = await (transactionTypeResponse.json());
        const randomTxType = Math.floor(Math.random() * (transactionTypes.length - 1));
        return (transactionTypes[randomTxType].id)
    }

    const getEventCodes = async () => {
        const eventCodesResponse = await (await reeveService(request)).getEventCodes(authToken);
        expect(eventCodesResponse.status()).toEqual(HttpStatusCodes.success);
        const eventCodes: EventCodesDto[] = await (eventCodesResponse.json());
        const referenceCodes: ReferenceCodePair[] = eventCodes.map(eventCode => ({
            debitReferenceCode: eventCode.debitReferenceCode,
            creditReferenceCode: eventCode.creditReferenceCode
        }));
        return referenceCodes;
    }

    /**
     * Get two lists of accounts that has an event code
     * for the combination of debit and credit accounts.
     * The lists must allow at least two different debit/credit pairs,
     * one for each transaction item
     * @param eventCodes array of organization's event codes
     *
     */
    const getDebitAndCreditAccounts = async (eventCodes: ReferenceCodePair[]) => {
        const chartOfAccounts: AccountRefCodePair[] = await getChartOfAccounts();
        for (const eventCode of eventCodes) {
            if (eventCode.debitReferenceCode !== eventCode.creditReferenceCode) {
                const debitAndCreditAccounts: DebitAndCreditAccounts = {
                    debitAccounts: getAccountsByReferenceCode(chartOfAccounts, eventCode.debitReferenceCode),
                    creditAccounts: getAccountsByReferenceCode(chartOfAccounts, eventCode.creditReferenceCode)
                }
                if (getAccountPairs(debitAndCreditAccounts).length >= 2) return debitAndCreditAccounts;
            }
        }
        throw new Error("No event code with at least two different debit/credit account pairs found");
    }

    const getAccountsByReferenceCode = (chartOfAccounts: AccountRefCodePair[], referenceCode: string) =>
        chartOfAccounts
            .filter(account => account.referenceCode === referenceCode)
            .map(account => ({ accountCode: account.accountCode, accountName: account.accountName }));

    /**
     * All the unique debit/credit combinations where the debit account is different from the credit account
     */
    const getAccountPairs = (debitAndCreditAccounts: DebitAndCreditAccounts) => {
        const accountPairs = new Map<string, TransactionItemAccounts>();
        for (const debitAccount of debitAndCreditAccounts.debitAccounts) {
            for (const creditAccount of debitAndCreditAccounts.creditAccounts) {
                if (debitAccount.accountCode !== creditAccount.accountCode) {
                    accountPairs.set(debitAccount.accountCode + "|" + creditAccount.accountCode,
                        { debitAccount: debitAccount, creditAccount: creditAccount });
                }
            }
        }
        return [...accountPairs.values()]
    }

    /**
     * Pick two different debit/credit pairs, one for each transaction item.
     * The backend merges the items with the same pair and drops the items with the same
     * debit and credit account, so both cases would end in a one item transaction
     * @return [debit item accounts, credit item accounts]
     */
    const getTransactionItemsAccounts = (debitAndCreditAccounts: DebitAndCreditAccounts) => {
        const accountPairs = getAccountPairs(debitAndCreditAccounts);
        if (accountPairs.length < 2) throw new Error("At least two different debit/credit account pairs are needed, found: "
            + JSON.stringify(accountPairs));
        return faker.helpers.arrayElements(accountPairs, 2)
    }

    /**
     * Use the given account as the only debit account and get the credit accounts
     * of an event code that has the debit account reference code
     * @param debitAccount account to be used in the debit side of the transaction
     */
    const getCreditAccountsForDebitAccount = async (debitAccount: AccountRefCodePair) => {
        const eventCodes = await getEventCodes();
        const chartOfAccounts: AccountRefCodePair[] = await getChartOfAccounts();
        let creditAccounts: AccountCodeAndNamePair[] = [];
        for (const eventCode of eventCodes) {
            if (eventCode.debitReferenceCode === debitAccount.referenceCode
                && eventCode.debitReferenceCode !== eventCode.creditReferenceCode) {
                creditAccounts = chartOfAccounts
                    .filter(account => account.referenceCode === eventCode.creditReferenceCode
                        && account.accountCode !== debitAccount.accountCode)
                    .map(account => ({ accountCode: account.accountCode, accountName: account.accountName }));
                if (creditAccounts.length >= 2) break;
            }
        }
        if (creditAccounts.length < 2) throw new Error("At least two credit accounts are needed for the reference code "
            + debitAccount.referenceCode + " of the debit account " + debitAccount.accountCode);
        const debitAndCreditAccounts: DebitAndCreditAccounts = {
            debitAccounts: [{ accountCode: debitAccount.accountCode, accountName: debitAccount.accountName }],
            creditAccounts: creditAccounts
        }
        return debitAndCreditAccounts
    }

    const getChartOfAccounts = async () => {
        const chartOfAccountsResponse = await (await reeveService(request)).getChartOfAccounts(authToken);
        expect(chartOfAccountsResponse.status()).toEqual(HttpStatusCodes.success);
        const chartOfAccounts: AccountRefCodePair[] = (await (chartOfAccountsResponse).json())
            .map(chartOfAccount => ({
                accountCode: chartOfAccount.customerCode,
                referenceCode: chartOfAccount.eventRefCode,
                accountName: chartOfAccount.name
            }))
        return chartOfAccounts
    }

    const createTransactionItem = async (transactionItemCommonData: TransactionItemCsvDto, amount: string,
                                         isDebit: boolean, itemAccounts: TransactionItemAccounts) => {
        const transactionItem: TransactionItemCsvDto = {
            TxNumber: transactionItemCommonData.TxNumber,
            TxDate: transactionItemCommonData.TxDate,
            TxType: transactionItemCommonData.TxType,
            FxRate: "1",
            AmountLcyDebit: "",
            AmountLcyCredit: "",
            AmountFcyDebit: "",
            AmountFcyCredit: "",
            DebitCode: "",
            DebitName: "",
            CreditCode: "",
            CreditName: "",
            ProjectCode: "",
            DocumentName: transactionItemCommonData.DocumentName,
            TxCurrency: "CHF",
            VatRate: "",
            VatCode: "",
            TxCostCenter: "",
            CounterParty: "",
            CounterpartyName: "",
        }
        if (isDebit) {
            transactionItem.AmountLcyDebit = amount;
            transactionItem.AmountFcyDebit = amount;
        } else {
            transactionItem.AmountLcyCredit = amount;
            transactionItem.AmountFcyCredit = amount;
        }
        transactionItem.DebitCode = itemAccounts.debitAccount.accountCode;
        transactionItem.DebitName = itemAccounts.debitAccount.accountName;
        transactionItem.CreditCode = itemAccounts.creditAccount.accountCode;
        transactionItem.CreditName = itemAccounts.creditAccount.accountName;
        return transactionItem
    }

    return {
        createReadyToApproveTransaction: createCSVTransactionReadyToApprove,
        createReadyToApproveTransactionWithDebitAccount: createCSVTransactionReadyToApproveWithDebitAccount,
        createCSVTransactionPending,
        createCSVTransactionPendingByCostCenter,
        createCSVTransactionInvalid
    }

}