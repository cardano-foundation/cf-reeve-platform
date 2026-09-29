package org.cardanofoundation.lob.app.reporting.model.enums;

import java.util.Arrays;
import java.util.Optional;

public enum ReportFieldDateRange {

    PERIOD("Period-Only balance"),
    ACCUMULATED_START_TO_PERIOD_END("End-of-Period balance"),
    ACCUMULATED_YEAR_TO_PERIOD_END("Year-to-Date balance"),
    ACCUMULATED_PREVIOUS_YEAR_TO_PREVIOUS_YEAR_END("Previous-Year balance"),
    ACCUMULATED_PREVIOUS_YEAR_TO_PERIOD_END("Previous-Year-to-Date balance"),
    ACCUMULATED_START_TO_PREVIOUS_YEAR_END("End-of-Previous-Year balance");

    private final String csvLabel;

    ReportFieldDateRange(String csvLabel) {
        this.csvLabel = csvLabel;
    }

    public String getCsvLabel() {
        return csvLabel;
    }

    public static Optional<ReportFieldDateRange> fromCsvLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(range -> range.csvLabel.equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
