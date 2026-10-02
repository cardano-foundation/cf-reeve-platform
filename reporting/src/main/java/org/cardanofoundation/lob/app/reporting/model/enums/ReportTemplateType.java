package org.cardanofoundation.lob.app.reporting.model.enums;

import java.util.Arrays;
import java.util.Optional;

public enum ReportTemplateType {
    BALANCE_SHEET("Balance sheet"),
    INCOME_STATEMENT("Income statement"),
    CUSTOM("Custom");

    private final String csvLabel;

    ReportTemplateType(String csvLabel) {
        this.csvLabel = csvLabel;
    }

    public String getCsvLabel() {
        return csvLabel;
    }

    public static Optional<ReportTemplateType> fromCsvLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(type -> type.csvLabel.equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
