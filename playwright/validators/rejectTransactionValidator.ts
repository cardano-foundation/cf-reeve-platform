import {TransactionDetailDto} from "../api/dtos/transactionDetailsDto";
import {expect} from "@playwright/test";
import {RejectionStatistic} from "../api/dtos/RejectTransactionDto";

export async function rejectTransactionValidator() {
    const validateTransactionWithInvalidStatus = async (transactionData: TransactionDetailDto, rejectedReason: string) => {
        expect(transactionData.statistic, "Transaction should have the invalid status")
            .toEqual(RejectionStatistic.INVALID)
        transactionData.items.forEach((item, index) => {
            expect(item.rejectionReason,`Tx item ${index} with id ${item.id}
             has not the expected rejection reason`).toEqual(rejectedReason)
        })
    }
    const validateTransactionWithPendingStatus = async (transactionData: TransactionDetailDto, rejectedReason: string) => {
        expect(transactionData.statistic, "Transaction should have the invalid status")
            .toEqual(RejectionStatistic.PENDING)
        transactionData.items.forEach((item, index) => {
            expect(item.rejectionReason,`Tx item ${index} with id ${item.id}
             has not the expected rejection reason`).toEqual(rejectedReason)
        })
    }
    return {
        validateTransactionWithInvalidStatus,
        validateTransactionWithPendingStatus
    }
}