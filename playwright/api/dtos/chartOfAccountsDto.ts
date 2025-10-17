export interface ChartOfAccountsDto {
    customerCode: string;
    eventRefCode: string;
    name: string;
    subType: number;
    type: number;
    active: boolean;
    error: any;
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

export interface DebitAndCreditAccounts {
    debitAccounts: AccountCodeAndNamePair[];
    creditAccounts: AccountCodeAndNamePair[];
}