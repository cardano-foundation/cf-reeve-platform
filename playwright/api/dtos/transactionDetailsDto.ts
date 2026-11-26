export interface TransactionItemDto {
    id: string;
    accountDebitCode: string;
    accountDebitName: string;
    accountDebitRefCode: string;
    accountCreditCode: string;
    accountCreditName: string;
    accountCreditRefCode: string;
    amountFcy: number;
    amountLcy: number;
    fxRate: number;
    costCenterCustomerCode: string;
    costCenterName: string;
    parentCostCenterCustomerCode: string;
    parentCostCenterName: string;
    projectCustomerCode: string;
    projectName: string;
    parentProjectCustomerCode: string;
    parentProjectName: string;
    accountEventCode: string;
    accountEventName: string;
    documentNum: string;
    documentCurrencyCustomerCode: string;
    vatCustomerCode: string;
    vatRate: number;
    counterpartyCustomerCode: string;
    counterpartyType: string;
    counterpartyName: string;
    rejectionReason: string | null;
}

export interface TransactionDetailDto {
    id: string;
    internalTransactionNumber: string;
    entryDate: string;
    transactionType: string;
    dataSource: string;
    status: string;
    statistic: string;
    validationStatus: string;
    ledgerDispatchStatus: string;
    transactionApproved: boolean;
    ledgerDispatchApproved: boolean;
    amountTotalLcy: number;
    itemRejection: boolean;
    reconciliationSource: string;
    reconciliationSink: string;
    reconciliationFinalStatus: string;
    reconciliationRejectionCode: string[];
    itemCount: number;
    items: TransactionItemDto[];
    violations: string[];
}