package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import java.util.Map;

// This class contains mappings if any needed for sorting entities
public class SortFieldMappings {

    private SortFieldMappings() {
        // Private constructor to prevent instantiation
    }

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
        "reconcilationSink", "reconcilation.sink"
    );
}
