package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.util.Set;

public enum LedgerDispatchStatus {

    NOT_DISPATCHED, // not dispatched to blockchain(s) yet

    MARK_DISPATCH, // acking that we stored in the database of blockchain publisher (marked for dispatch)

    DISPATCHED, // dispatched to blockchain(s) - tx hash

    COMPLETED, // tx hash is ready and provided and in addition we have decent finality score to consider it completed

    FINALIZED, // finalised on blockchain(s) - tx hash (12 hours)

    RETRYING,

    FAILED;

    public static Set<LedgerDispatchStatus> allDispatchedStatuses() {
        return Set.of(MARK_DISPATCH, DISPATCHED, COMPLETED, FINALIZED, RETRYING, FAILED);
    }

    /**
     * Dispatchable means that we can dispatch the transaction line to the blockchain(s)
     */
    public boolean isDispatchable() {
        return this == NOT_DISPATCHED;
    }

}
