export interface OpeningBalance {
    balanceFCY: number;
    balanceLCY: number;
    originalCurrencyIdFCY: string;
    originalCurrencyIdLCY: string;
    balanceType: string;
    date: string;
}

export interface ChartOfAccountsDto {
    customerCode: string;
    eventRefCode: string;
    name: string;
    subType: number;
    subTypeName: string;
    type: number;
    typeName: string;
    currency: string;
    counterParty: string;
    active: boolean;
    parentCustomerCode: string;
    openingBalance?: OpeningBalance;
    error: any;
}

export interface UpdateChartOfAccountsDto {
    customerCode: string;
    eventRefCode: string;
    name: string;
    subType: number;
    type: number;
    currency: string;
    counterParty: string;
    parentCustomerCode: string;
    active: boolean;
    openingBalance?: OpeningBalance;
}

export interface AccountRefCodePair {
    accountCode: string;
    referenceCode: string;
    accountName: string;
}

export interface AccountCodeAndNamePair {
    accountCode: string;
    accountName: string;
}

export interface TransactionItemAccounts {
    debitAccount: AccountCodeAndNamePair;
    creditAccount: AccountCodeAndNamePair;
}

export interface DebitAndCreditAccounts {
    debitAccounts: AccountCodeAndNamePair[];
    creditAccounts: AccountCodeAndNamePair[];
}

export interface ChartOfAccountErrorDto {
    type: string;
    title: string;
    status: number;
    detail: string;
}

export interface ChartSubTypeDto {
    id: number;
    organisationId: string;
    name: string;
}

export interface ChartTypeDto {
    id: number;
    organisationId: string;
    name: string;
    subType: ChartSubTypeDto[];
}
