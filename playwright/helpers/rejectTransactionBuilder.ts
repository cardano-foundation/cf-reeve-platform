import {BatchResponse} from "../api/dtos/batchDto";
import {RejectTransactionDto} from "../api/dtos/RejectTransactionDto";

export async function rejectTransactionBuilder(transactionBatchDetails: BatchResponse) {
    const createRejectTransactionPayloadWithReason = async (rejectionReason: string) => {
        const rejectTransactionPayload : RejectTransactionDto = {
            organisationId: transactionBatchDetails.organisationId,
            transactionId: transactionBatchDetails.transactions[0].id,
            transactionItemsRejections: transactionBatchDetails.transactions[0].items.map(
                item => ({
                    txItemId: item.id,
                    rejectionCode: rejectionReason
                })
            )
        }
        return rejectTransactionPayload
    }

    return {
        createRejectTransactionPayloadWithReason
    }
}