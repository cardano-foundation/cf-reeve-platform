package org.cardanofoundation.lob.app.reporting.util;

import java.util.Map;


public class PageableFieldMappings {

    private PageableFieldMappings() {} // private constructor to prevent instantiation

    public static final Map<String, String> REPORT_MAPPINGS = Map.of(
        "blockchainTxId", "blockchainHash",
            "intervalType", "intervalType",
            "reportTemplateId", "reportTemplate.id",
            "isPublished", "ledgerDispatchApproved"
    );

    public static final Map<String, String> TEMPLATE_MAPPINGS = Map.of(
    );
}
