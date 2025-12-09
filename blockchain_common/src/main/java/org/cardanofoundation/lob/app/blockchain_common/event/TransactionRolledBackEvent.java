package org.cardanofoundation.lob.app.blockchain_common.event;

import org.springframework.context.ApplicationEvent;

public class TransactionRolledBackEvent extends ApplicationEvent {
    private final String transactionId;

    public TransactionRolledBackEvent(Object source, String transactionId) {
        super(source);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
