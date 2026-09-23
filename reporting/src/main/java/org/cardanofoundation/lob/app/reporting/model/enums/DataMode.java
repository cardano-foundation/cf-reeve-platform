package org.cardanofoundation.lob.app.reporting.model.enums;

import java.util.Arrays;
import java.util.Optional;

public enum DataMode {
    SYSTEM("Automatic"),
    USER("Manual");

    private final String csvLabel;

    DataMode(String csvLabel) {
        this.csvLabel = csvLabel;
    }

    public String getCsvLabel() {
        return csvLabel;
    }

    public static Optional<DataMode> fromCsvLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(mode -> mode.csvLabel.equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
