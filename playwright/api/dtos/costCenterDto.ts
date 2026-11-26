export interface CostCenterDto {
    customerCode: string;
    name: string;
    parent?: {
        customerCode: string;
        name: string;
        active: boolean;
        error: null;
    };
    active: boolean;
    error: null;
}

export interface UpdateCostCenterDto {
    customerCode: string;
    name: string;
    active: boolean;
}