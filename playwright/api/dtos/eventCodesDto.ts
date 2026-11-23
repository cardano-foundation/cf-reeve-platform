export interface EventCodesDto {
    organisationId: string;
    debitReferenceCode: string;
    creditReferenceCode: string;
    customerCode: string;
    description: string;
    active: boolean;
    error?: ErrorDetails;
}

export interface ErrorDetails {
    instance: string;
    type: string;
    parameters?: {
        additionalProp1?: any;
        additionalProp2?: any;
        additionalProp3?: any;
    };
    title: string;
    status: StatusInfo;
    detail: string;
}

export interface StatusInfo {
    reasonPhrase: string;
    statusCode: number;
}

export interface ReferenceCodePair {
    debitReferenceCode: string;
    creditReferenceCode: string;
}