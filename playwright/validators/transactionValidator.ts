import {TransactionItemCsvDto} from "../api/dtos/transactionItemCsvDto";
import {BatchResponse, TransactionItem} from "../api/dtos/batchDto";
import {expect} from "@playwright/test";
import {log} from "../utils/logger";
import {BatchesStatusCodes} from "../api/api-helpers/batches-status-codes";
import {TransactionPendingInvalidStatus} from "../helpers/transaction-pending-invalid-status";

export async function transactionValidator() {
    const validateImportedTxWithStatus = async (transactionCsvData: TransactionItemCsvDto[],
                                            importedBatchDetails: BatchResponse, transactionStatus: string) => {
        const debitCsvItem: TransactionItemCsvDto = await extractCsvTxItem(transactionCsvData, true);
        const creditCsvItem: TransactionItemCsvDto = await extractCsvTxItem(transactionCsvData, false);
        const txItems = importedBatchDetails.transactions[0].items
        const txDebitItem = txItems.find(tx =>
            tx.accountDebitCode == debitCsvItem.DebitCode && tx.accountCreditCode == debitCsvItem.CreditCode)
        const txCreditItem = txItems.find(tx =>
            tx.accountDebitCode == creditCsvItem.DebitCode && tx.accountCreditCode == creditCsvItem.CreditCode)
        if(transactionStatus == BatchesStatusCodes.APPROVE){
            expect(importedBatchDetails.batchStatistics.approve, "Imported batch should have transaction in ready to approve status ")
                .toEqual(importedBatchDetails.totalTransactionsCount)
        }
        if(transactionStatus == BatchesStatusCodes.PENDING){
            expect(importedBatchDetails.batchStatistics.pending, "Imported batch should have transaction in pending status ")
                .toEqual(importedBatchDetails.totalTransactionsCount)
        }
        if(transactionStatus == BatchesStatusCodes.INVALID){
            expect(importedBatchDetails.batchStatistics.invalid, "Imported batch should have transaction in invalid status ")
                .toEqual(importedBatchDetails.totalTransactionsCount)
        }
        expect(importedBatchDetails.transactions[0].items.length, "The sent transaction items are not the same imported in the system ")
            .toEqual(transactionCsvData.length)
        expect(importedBatchDetails.transactions[0].internalTransactionNumber, "The transaction number is not the same that was sent")
            .toEqual(transactionCsvData[0].TxNumber)
        expect(importedBatchDetails.transactions[0].transactionType,"The transaction type is not the same that was sent")
            .toEqual(transactionCsvData[0].TxType)
        await validateTxItem(txDebitItem, debitCsvItem, true);
        await validateTxItem(txCreditItem, creditCsvItem, false);
    }
    const validateTxItem = async (txItem: TransactionItem, txCsvItem: TransactionItemCsvDto, isDebit: boolean) => {
        expect(txItem.accountDebitCode, "The sent debit code is not the same imported in the system")
            .toEqual(txCsvItem.DebitCode);
        expect(txItem.accountCreditCode, "The sent credit code is not the same imported in the system")
            .toEqual(txCsvItem.CreditCode);
        expect(txItem.documentNum, "The sent document number is not the same imported in the system")
            .toEqual(txCsvItem.DocumentName)
        if(isDebit == true){
            expect(txItem.amountLcy, "The LCY amount in debit item is not the same imported in the system")
                .toEqual(parseFloat(txCsvItem.AmountLcyDebit))
        }else {
            expect(Math.abs(txItem.amountLcy), "The LCY amount in credit item is not the same imported in the system")
                .toEqual(parseFloat(txCsvItem.AmountLcyCredit))
        }
    }
    const validatePendingCondition = async (importedBatchDetails: BatchResponse, expectedReason: string) => {
        expect(importedBatchDetails.transactions[0].violations[0].code, "The expected pending reason is wrong")
            .toEqual(expectedReason)
    }
    const validateInvalidCondition = async (importedBatchDetails: BatchResponse, expectedReason: string) => {
        if(expectedReason == TransactionPendingInvalidStatus.UNBALANCED_TRANSACTION){
            expect(importedBatchDetails.transactions[0].violations).toEqual(
                expect.arrayContaining([
                    expect.objectContaining({code: TransactionPendingInvalidStatus.FCY_BALANCE_MUST_BE_ZERO}),
                    expect.objectContaining({code: TransactionPendingInvalidStatus.LCY_BALANCE_MUST_BE_ZERO})
                ])
            )
        }else {
            expect(importedBatchDetails.transactions[0].violations[0].code, "The expected invalid reason is wrong")
                .toEqual(expectedReason)
        }
    }
    const extractCsvTxItem = async (transactionCsvData: TransactionItemCsvDto[], isDebit: boolean) => {
        return transactionCsvData.find(tx => {
            let amount: number
            if(isDebit == true){
                amount = parseFloat(tx.AmountLcyDebit);
                return !isNaN(amount) && amount > 0;
            }
            amount = parseFloat(tx.AmountLcyCredit);
            return !isNaN(amount) && amount > 0
        })
    }
    const validateTransactionContainsAccount = async (importedBatchDetails: BatchResponse, accountCode: string) => {
        const txItems = importedBatchDetails.transactions[0].items;
        expect(txItems.some(txItem => txItem.accountDebitCode == accountCode || txItem.accountCreditCode == accountCode),
            "The imported transaction " + importedBatchDetails.transactions[0].internalTransactionNumber
            + " should contain the account " + accountCode + " but has the accounts: "
            + JSON.stringify(txItems.map(txItem => ({debit: txItem.accountDebitCode, credit: txItem.accountCreditCode}))))
            .toBeTruthy()
    }
    return {
        validateImportedTxWithStatus,
        validateTransactionContainsAccount,
        validatePendingCondition,
        validateInvalidCondition
    }
}