export enum RejectionCode {
    INCORRECT_AMOUNT = 'INCORRECT_AMOUNT',
    INCORRECT_COST_CENTER = 'INCORRECT_COST_CENTER',
    INCORRECT_PROJECT = 'INCORRECT_PROJECT',
    INCORRECT_CURRENCY = 'INCORRECT_CURRENCY',
    INCORRECT_VAT_CODE = 'INCORRECT_VAT_CODE',
    REVIEW_PARENT_COST_CENTER = 'REVIEW_PARENT_COST_CENTER',
    REVIEW_PARENT_PROJECT_CODE = 'REVIEW_PARENT_PROJECT_CODE'
}

export enum RejectionStatistic {
    INVALID = 'INVALID',
    PENDING = 'PENDING'
}

// Request DTOs
export interface TransactionItemRejectionDto {
    txItemId: string;
    rejectionCode: string;
}

export interface RejectTransactionDto {
    organisationId: string;
    transactionId: string;
    transactionItemsRejections: TransactionItemRejectionDto[];
}

// Response DTOs
export interface RejectionItemResponseDto {
    transactionItemId: string;
    success: boolean;
    error: string | null;
}

export interface RejectTransactionResponseDto {
    transactionId: string;
    success: boolean;
    statistic: RejectionStatistic;
    items: RejectionItemResponseDto[];
    error: string | null;
}