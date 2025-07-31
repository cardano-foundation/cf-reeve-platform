package org.cardanofoundation.lob.app.accounting_reporting_core.utils;

import java.util.Map;

// This class contains mappings if any needed for sorting entities
public class SortFieldMappings {

    public static final Map<String, String> TRANSACTION_ENTITY_FIELD_MAPPINGS = Map.of(
        "reconciliationSource", "reconcilation.source",
        "reconciliationSink", "reconcilation.sink",
        "reconciliationFinalStatus", "reconcilation.finalStatus"
    );
}
