interface BatchStatistics {
    batchId: string;
    invalid: number;
    pending: number;
    approve: number;
    publish: number;
    published: number;
    total: number;
}

interface FilteringParameters {
    transactionTypes: string[];
    from: string;
    to: string;
    accountingPeriodFrom: string;
    accountingPeriodTo: string;
    transactionNumbers: string[] | number[];
}
export interface Batch {
    id: string;
    createdAt: string;
    updatedAt: string;
    createdBy: string;
    updateBy: string;
    organisationId: string;
    status: string;
    batchStatistics: BatchStatistics;
    filteringParameters: FilteringParameters;
}

export interface BatchData {
    total: number;
    batchs: Batch[];
}