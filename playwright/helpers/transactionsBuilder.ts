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

export async function transactionsBuilder(request: APIRequestContext, authToken: string){
    const createCSVTransactionReadyToApprove = async (transactionDataToImport: TransactionItemCsvDto[]) => {
        const columns = await getTransactionCSVHeaders();
        const rows = await createValidTransactionData(transactionDataToImport)
       return  await saveCSV(columns, rows,  "transaction.csv");
    }
    const getTransactionCSVHeaders = async () => {
        try {
            const headers = await fs.promises.readFile('../playwright/utils/transactionCSVHeaders.txt', 'utf-8')
            return headers
                .split(',')
                .map(header => header.trim())
        }catch (error) {
            log.error("Error trying to read file: ",error);
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
        // Create a random short hash for transaction Number
        const txNumber = "TEST-"+Math.random().toString(36).substring(2, 2 + 8);
        const txDate = getDateInThePast(2,"/");
        const txType = await getTransactionType();
        const amountForTxItem = (Math.floor(Math.random() * 100000) + 1).toString();
        const documentName = "TEST-"+Math.random().toString(36).substring(2, 2 + 8);
        const eventCodes = await getEventCodes();
        const debitAndCreditAccounts = await getDebitAndCreditAccounts(eventCodes);
        const debitTxItem = await createTransactionItem(txNumber, txDate, txType, amountForTxItem,
            true, documentName, debitAndCreditAccounts);
        const creditTxItem = await createTransactionItem(txNumber, txDate, txType, amountForTxItem,
            false, documentName,debitAndCreditAccounts);
        transactionDataToImport.push(debitTxItem);
        transactionDataToImport.push(creditTxItem);
        const rows: string[][] = [];
        rows.push(Object.values(debitTxItem));
        rows.push(Object.values(creditTxItem))
        return rows
    }

    const getTransactionType = async () => {
        const transactionTypeResponse = await (await reeveService(request))
            .getTransactionTypes(authToken);
        expect(transactionTypeResponse.status()).toEqual(HttpStatusCodes.success);
        const transactionTypes: TransactionTypeDto[] = await (transactionTypeResponse.json());
        const randomTxType = Math.floor(Math.random() * (transactionTypes.length -1));
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
        while (accountsMatch == false){
            if(eventCodes[index].debitReferenceCode != eventCodes[index].creditReferenceCode ){
                debitAccounts = chartOfAccounts.filter(chartOfAccount =>
                    chartOfAccount.referenceCode === eventCodes[index].debitReferenceCode)
                    .map(chartOfAccount => ({
                        accountCode: chartOfAccount.accountCode,
                        accountName: chartOfAccount.accountName
                    }));
                if(debitAccounts.length>=1){
                    creditAccounts = chartOfAccounts.filter(chartOfAccount =>
                        chartOfAccount.referenceCode === eventCodes[index].creditReferenceCode
                    && chartOfAccount.accountCode !== debitAccounts[0].accountCode)
                        .map(chartOfAccount => ({
                            accountCode: chartOfAccount.accountCode,
                            accountName: chartOfAccount.accountName
                        }));
                }
                if(creditAccounts!=null) {
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
            .map( chartOfAccount => ({
                accountCode: chartOfAccount.customerCode,
                referenceCode: chartOfAccount.eventRefCode,
                accountName: chartOfAccount.name
            }))
        return chartOfAccounts
    }

    const createTransactionItem = async (txNumber: string, txDate:string, txType: string, amount:string,
                                         isDebit: boolean, documentName: string,
                                         debitAndCreditAccounts: DebitAndCreditAccounts) => {
        let randomIndexDebit = Math.floor(Math.random() * debitAndCreditAccounts.debitAccounts.length)
        let randomIndexCredit = Math.floor(Math.random() * debitAndCreditAccounts.creditAccounts.length)
        const transactionItem: TransactionItemCsvDto = {
            TxNumber: txNumber,
            TxDate: txDate,
            TxType: txType,
            FxRate: "1",
            AmountLcyDebit:"",
            AmountLcyCredit:"",
            AmountFcyDebit:"",
            AmountFcyCredit:"",
            DebitCode:"",
            DebitName:"",
            CreditCode:"",
            CreditName:"",
            ProjectCode:"",
            DocumentName:documentName,
            TxCurrency:"CHF",
            VatRate:"",
            VatCode:"",
            TxCostCenter:"",
            CounterParty:"",
            CounterpartyName:"",
        }
        if(isDebit){
            transactionItem.AmountLcyDebit= amount;
            transactionItem.AmountFcyDebit = amount;
        }else {
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
        createReadyToApproveTransaction: createCSVTransactionReadyToApprove
    }

}