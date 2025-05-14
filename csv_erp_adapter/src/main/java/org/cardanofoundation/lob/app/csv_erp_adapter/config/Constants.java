package org.cardanofoundation.lob.app.csv_erp_adapter.config;

public class Constants {

    private Constants() {
        // Prevent instantiation
    }

    public static final String RECONCILATION_NOT_FOUND = "RECONCILATION_NOT_FOUND";
    public static final String RECONCILATION_ID = "reconcilationId";
    public static final String TRANSACTION_CONVERSION_ERROR = "TRANSACTION_CONVERSION_ERROR";
    public static final String NO_TRANSACTION_LINES = "NO_TRANSACTION_LINES";
    public static final String CSV_PARSING_ERROR = "CSV_PARSING_ERROR";
    public static final String ORGANISATION_MISMATCH = "ORGANISATION_MISMATCH";
    public static final String BATCH_NOT_FOUND = "BATCH_NOT_FOUND";
    public static final String NO_SYSTEM_PARAMETERS = "NO_SYSTEM_PARAMETERS";
    public static final String EMPTY_FILE = "EMPTY_FILE";
    public static final String IGNORING_EVENT_FOR_EXTRACTOR_TYPE = "Ignoring event for extractor type: {}";
    public static final String BATCH_ID = "batchId";
    public static final String TEMPORARY_FILE_CACHE_SIZE_LOG = "Temporary file cache size: {}";
}
