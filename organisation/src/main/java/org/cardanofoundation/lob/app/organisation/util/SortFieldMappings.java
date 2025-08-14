package org.cardanofoundation.lob.app.organisation.util;

import java.util.Map;

public class SortFieldMappings {

    private SortFieldMappings() {
        // Private constructor to prevent instantiation
    }

    public static final Map<String, String> CHART_OF_ACCOUNT_MAPPINGS = Map.of(
            "customerCode", "id.customerCode"
    );

    public static final Map<String, String> COST_CENTER_MAPPINGS = Map.of(
            "customerCode", "id.customerCode"
    );

    public static final Map<String, String> PROJECT_MAPPINGS = Map.of(
            "customerCode", "id.customerCode"
    );

    public static final Map<String, String> ACCOUNT_EVENT_MAPPINGS = Map.of(
            "debitReferenceCode", "id.debitReferenceCode",
            "creditReferenceCode", "id.creditReferenceCode"
    );

    public static final Map<String, String> CURRENCY_MAPPINGS = Map.of(
            "customerCode", "id.customerCode"
    );

    public static final Map<String, String> VAT_MAPPINGS = Map.of(
            "customerCode", "id.customerCode"
    );
}
