package org.cardanofoundation.lob.app.reporting.model.enums;

import java.util.Arrays;
import java.util.Optional;

public enum FieldSign {

    POSITIVE("Positive", false),
    NEGATIVE("Negative", true);

    private final String csvLabel;
    private final boolean negated;

    FieldSign(String csvLabel, boolean negated) {
        this.csvLabel = csvLabel;
        this.negated = negated;
    }

    public String getCsvLabel() {
        return csvLabel;
    }

    public boolean isNegated() {
        return negated;
    }

    public static FieldSign fromNegated(boolean negated) {
        return negated ? NEGATIVE : POSITIVE;
    }

    public static Optional<FieldSign> fromCsvLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String trimmed = label.trim();
        return Arrays.stream(values())
                .filter(sign -> sign.csvLabel.equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
