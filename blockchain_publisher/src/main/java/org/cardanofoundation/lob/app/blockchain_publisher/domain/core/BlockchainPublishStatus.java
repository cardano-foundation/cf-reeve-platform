package org.cardanofoundation.lob.app.blockchain_publisher.domain.core;

import java.util.Set;

public enum BlockchainPublishStatus {

    STORED,

    SUBMITTED, // submitted and setting in the mem pool for now

    VISIBLE_ON_CHAIN, // confirmed to be visible on-chain

    COMPLETED, // confirmed on-chain and e.g. 5 - 40 blocks passed

    ROLLBACKED, // signal to resubmit the transaction since it disappeared from on chain

    FINALIZED, // finalised on blockchain(s) - tx hash (12 hours)

    ERROR;

    public static Set<BlockchainPublishStatus> toDispatchStatuses() {
        return Set.of(STORED, ROLLBACKED);
    }

    public static Set<BlockchainPublishStatus> notFinalised() {
        return Set.of(SUBMITTED, VISIBLE_ON_CHAIN, COMPLETED);
    }

    public static Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain() {
        return Set.of(SUBMITTED, VISIBLE_ON_CHAIN, COMPLETED);
    }

    public static Set<BlockchainPublishStatus> onChainAndNotFinalised() {
        return Set.of(VISIBLE_ON_CHAIN, COMPLETED);
    }

}
