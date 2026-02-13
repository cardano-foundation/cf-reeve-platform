package org.cardanofoundation.lob.app.accounting_reporting_core.utils;


import java.util.Map;

public class PageableFieldMappings {

        private PageableFieldMappings() {} // Private constructor to prevent instantiation

        public static final Map<String, String> TRANSACTION_ENTITY_FIELD_MAPPINGS = Map.of(
        "reconciliationSource", "reconcilation.source",
        "reconciliationSink", "reconcilation.sink",
        "reconciliationFinalStatus", "reconcilation.finalStatus",
        "dataSource", "extractorType",
        "status", "overallStatus",
        "statistic", "processingStatus",
        "validationStatus", "automatedValidationStatus"
        );

        public static final Map<String, String> RECONCILATION_FIELD_MAPPINGS = Map.of(
            "dataSource", "extractorType",
            "status", "overallStatus",
            "amountTotalLcy", "totalAmountLcy",
            "reconciliationSource", "reconcilation.source",
            "reconcilationSink", "reconcilation.sink",
            "reconciliationDate", "createdAt"
        );

        public static final Map<String, String> EXTRACTION_SEARCH_FIELD_MAPPINGS = Map.ofEntries(
                        Map.entry("transactionInternalNumber",
                                        "transaction.internalTransactionNumber"),
                        Map.entry("transactionID", "transaction.id"),
                        Map.entry("entryDate", "transaction.entryDate"),
                        Map.entry("transactionType", "transaction.transactionType"),
                        Map.entry("reconciliation", "transaction.reconcilation.finalStatus"),
                        Map.entry("accountDebitCode", "accountDebit.code"),
                        Map.entry("accountCreditCode", "accountCredit.code"),
                        Map.entry("accountDebitName", "accountDebit.name"),
                        Map.entry("accountCreditName", "accountCredit.name"),
                        Map.entry("accountDebitRefCode", "accountDebit.refCode"),
                        Map.entry("accountCreditRefCode", "accountCredit.refCode"),
                        Map.entry("costCenterCustomerCode", "costCenter.customerCode"),
                        Map.entry("costCenterName", "costCenter.name"),
                        Map.entry("projectCustomerCode", "project.customerCode"),
                        Map.entry("projectName", "project.name"),
                        Map.entry("accountEventCode", "accountEvent.code"),
                        Map.entry("accountEventName", "accountEvent.name"),
                        Map.entry("documentNum", "document.num"),
                        Map.entry("documentCurrencyCustomerCode", "document.currency.customerCode"),
                        Map.entry("vatRate", "document.vat.rate"),
                        Map.entry("blockChainHash", "transaction.ledgerDispatchReceipt.primaryBlockchainHash"),
                        Map.entry("vatCustomerCode", "document.vat.customerCode"),
                        Map.entry("parentCostCenterCustomerCode", "mappedCostCenter.parentCustomerCode"),
                        Map.entry("parentProjectCustomerCode", "mappedProject.parentCustomerCode"),
                        Map.entry("counterpartyCustomerCode", "document.counterparty.customerCode"),
                        Map.entry("counterpartyName", "document.counterparty.name"),
                        Map.entry("counterpartyType", "document.counterparty.type")
                );

}
