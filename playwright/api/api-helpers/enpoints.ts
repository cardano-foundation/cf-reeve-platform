import * as process from "process";

export class Reeve {
    static readonly BASE_URL = process.env.API_URL as string;
    static readonly LOGIN_URL = process.env.LOGIN_URL as string;

    static SignIn = class {
        public static get Base() {
            return `${Reeve.LOGIN_URL}/realms/reeve-master/protocol/openid-connect/token`;
        }
    };
    static Transactions = class {
        public static get Reject() {
            return `${Reeve.BASE_URL}/transaction/reject`
        }
        public static get Approve() {
            return `${Reeve.BASE_URL}/transactions/approve`
        }
        public static get Types() {
            return `${Reeve.BASE_URL}/transaction-types`
        }
        public static get Extraction() {
            return `${Reeve.BASE_URL}/extraction`
        }
        public static get Validation() {
            return `${Reeve.Transactions.Extraction}/validation`
        }
        public static get transactionById() {
            return `${Reeve.BASE_URL}/transactions/:txId`
        }
    }
    static Organization = class {
        public static get Base() {
            return `${Reeve.BASE_URL}/organisations`
        }
        public static get EventCodes() {
            return `${Reeve.Organization.Base}/:orgId/event-codes`
        }
        public static get ChartOfAccounts() {
            return `${Reeve.Organization.Base}/:orgId/chart-of-accounts`
        }
        public static get CostCenters() {
            return `${Reeve.Organization.Base}/:orgId/cost-centers`
        }
        public static get ChartTypes() {
            return `${Reeve.Organization.Base}/:orgId/chart-types`
        }
    }
    static Batches = class {
        public static get Batches() {
            return `${Reeve.BASE_URL}/batches`
        }
        public static get BatchById() {
            return `${Reeve.Batches.Batches}/:batchId`
        }
        public static get Reprocess() {
            return `${Reeve.Batches.Batches}/reprocess/:batchId`
        }
    }
}