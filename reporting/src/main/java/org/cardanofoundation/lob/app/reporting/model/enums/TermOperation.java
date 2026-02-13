package org.cardanofoundation.lob.app.reporting.model.enums;

public enum TermOperation {
    ADD("+"),
    SUBTRACT("-");

    private final String symbol;

    TermOperation(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
