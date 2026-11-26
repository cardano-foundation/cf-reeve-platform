export interface ApproveTransactionDto {
    organisationId: string;
    transactionIds: { id: string }[];
}