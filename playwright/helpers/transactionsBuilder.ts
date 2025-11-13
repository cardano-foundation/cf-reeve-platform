import {saveCSV} from "../utils/csvFileGenerator";
import * as fs from "fs";
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
    ChartOfAccountsDto,
    DebitAndCreditAccounts
} from "../api/dtos/chartOfAccountsDto";
import {TransactionPendingStatus} from "./transaction-pending-status";

export async function transactionsBuilder(request: APIRequestContext, authToken: string) {
    const createCSVTransactionReadyToApprove = async (transactionDataToImport: TransactionItemCsvDto[]) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createValidTransactionData(transactionDataToImport)
        const fileName = "Approve-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName);
    }
    const createCSVTransactionPending = async (transactionDataToImport: TransactionItemCsvDto[], pendingReason: string) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createPendingTransactionData(transactionDataToImport, pendingReason);
        const fileName = "Pending-" + Math.random().toString(36).substring(2, 2 + 8) + ".csv";
        return await saveCSV(columns, rows, fileName)
    }
    const getTransactionCSVHeaders = async () => {
        try {
            const headers = await fs.promises.readFile('../playwright/utils/transactionCSVHeaders.txt', 'utf-8')
            return headers
                .split(',')
                .map(header => header.trim())
        } catch (error) {
            log.error("Error trying to read file: ", error);
        }
    }

    /**
     * Create a transaction with just two transactions items,
     * txNumber Random short hash
     * documentName Random short hash
     * txType organization transaction type requested through API
     * debitTxItem accounts are requested through API in base of organization event codes
     * creditTxItem accounts are requested through API in base of organization event codes
     */
    const createValidTransactionData = async (transactionDataToImport: TransactionItemCsvDto[]) => {
        const transactionCommonData = await getTransactionCommonData()
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        const eventCodes = await getEventCodes();
        const debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            true, debitAndCreditAccounts);
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            false, debitAndCreditAccounts);
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
        const debitTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            true, debitAndCreditAccounts)
        await setPendingReason(debitTxItem, pendingReason);
        const creditTxItem = await createTransactionItem(transactionCommonData, amountForTxItem,
            false, debitAndCreditAccounts);
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
        if(pendingReason == TransactionPendingStatus.COST_CENTER_DATA_NOT_FOUND){
            transactionItem.TxCostCenter = Math.random().toString(36).substring(2, 2 + 8);
        }
        if(pendingReason == TransactionPendingStatus.VAT_DATA_NOT_FOUND){
            transactionItem.VatCode = Math.random().toString(36).substring(2, 2 + 8);
        }
        if(pendingReason == TransactionPendingStatus.CHART_OF_ACCOUNT_NOT_FOUND){
            transactionItem.DebitCode = Math.random().toString(36).substring(2, 2 + 8);
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
     * for the combination of debit and credit accounts
     * @param eventCodes array of organization's event codes
     *
     */
    const getDebitAndCreditAccounts = async (eventCodes: ReferenceCodePair[]) => {
        const chartOfAccounts: AccountRefCodePair[] = await getChartOfAccounts();
        let index = 0;
        let accountsMatch: boolean = false;
        let debitAccounts: AccountCodeAndNamePair[] | null;
        let creditAccounts: AccountCodeAndNamePair[] | null;
        while (accountsMatch == false) {
            if (eventCodes[index].debitReferenceCode != eventCodes[index].creditReferenceCode) {
                debitAccounts = chartOfAccounts.filter(chartOfAccount =>
                    chartOfAccount.referenceCode === eventCodes[index].debitReferenceCode)
                    .map(chartOfAccount => ({
                        accountCode: chartOfAccount.accountCode,
                        accountName: chartOfAccount.accountName
                    }));
                if (debitAccounts.length >= 1) {
                    creditAccounts = chartOfAccounts.filter(chartOfAccount =>
                        chartOfAccount.referenceCode === eventCodes[index].creditReferenceCode
                        && chartOfAccount.accountCode !== debitAccounts[0].accountCode)
                        .map(chartOfAccount => ({
                            accountCode: chartOfAccount.accountCode,
                            accountName: chartOfAccount.accountName
                        }));
                }
                if (creditAccounts != null) {
                    accountsMatch = true;
                }
            }
            index++;
        }
        const debitAndCreditAccounts: DebitAndCreditAccounts = {
            debitAccounts: debitAccounts,
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
                                         isDebit: boolean, debitAndCreditAccounts: DebitAndCreditAccounts) => {
        let randomIndexDebit = Math.floor(Math.random() * debitAndCreditAccounts.debitAccounts.length)
        let randomIndexCredit = Math.floor(Math.random() * debitAndCreditAccounts.creditAccounts.length)
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
        transactionItem.DebitCode = debitAndCreditAccounts.debitAccounts[randomIndexDebit].accountCode;
        transactionItem.DebitName = debitAndCreditAccounts.debitAccounts[randomIndexDebit].accountName;
        transactionItem.CreditCode = debitAndCreditAccounts.creditAccounts[randomIndexCredit].accountCode;
        transactionItem.CreditName = debitAndCreditAccounts.creditAccounts[randomIndexCredit].accountName;
        return transactionItem
    }

    return {
        createReadyToApproveTransaction: createCSVTransactionReadyToApprove,
        createCSVTransactionPending
    }

}