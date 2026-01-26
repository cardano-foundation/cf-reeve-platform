package org.cardanofoundation.lob.app.reporting.model.enums;

public enum ComparisonOperator {
    GREATER_THAN_OR_EQUAL(">="),
    EQUAL("="),
    LESS_THAN_OR_EQUAL("<=");

    private final String symbol;

    ComparisonOperator(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
