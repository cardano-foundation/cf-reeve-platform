export interface TransactionItemCsvDto {
    TxNumber?: string;
    TxDate?: string;
    TxType?: string;
    FxRate?: string;
    AmountLcyDebit?: string;
    AmountLcyCredit?: string;
    AmountFcyDebit?: string;
    AmountFcyCredit?: string;
    DebitCode?: string;
    DebitName?: string;
    CreditCode?: string;
    CreditName?: string;
    ProjectCode?: string;
    DocumentName?: string;
    TxCurrency?: string;
    VatRate?: string;
    VatCode?: string;
    TxCostCenter?: string;
    CounterParty?: string;
    CounterpartyName?: string;
}