
export interface BatchStatistics {
    batchId: string;
    invalid: number;
    pending: number;
    approve: number;
    publish: number;
    published: number;
    total: number;
}

export interface FilteringParameters {
    transactionTypes: string[];
    from: string;
    to: string;
    accountingPeriodFrom: string;
    accountingPeriodTo: string;
    transactionNumbers: string[];
}

export interface TransactionItem {
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
}

export interface Violation {
    severity: string;
    source: string;
    transactionItemId: string;
    code: string;
    bag: {
        customerCode: string;
        transactionNumber: string;
    };
}

export interface Transaction {
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
    items: TransactionItem[];
    violations: Violation[];
}

export interface BatchResponse {
    id: string;
    createdAt: string;
    updatedAt: string;
    createdBy: string;
    updateBy: string;
    organisationId: string;
    status: string;
    batchStatistics: BatchStatistics;
    filteringParameters: FilteringParameters;
    transactions: Transaction[];
    details: Record<string, any>;
    totalTransactionsCount: number;
}