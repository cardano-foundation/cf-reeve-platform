package org.cardanofoundation.lob.app.blockchain_publisher.domain.core;

import java.util.Arrays;
import java.util.Objects;

public record API3BlockchainTransaction(long creationSlot,
                                        byte[] serialisedTxData,
                                        String receiverAddress) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        API3BlockchainTransaction that = (API3BlockchainTransaction) o;

        return creationSlot == that.creationSlot &&
                Arrays.equals(serialisedTxData, that.serialisedTxData)
                && Objects.equals(receiverAddress, that.receiverAddress);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(creationSlot);
        result = 31 * result + Arrays.hashCode(serialisedTxData);
        result = 31 * result + Objects.hashCode(receiverAddress);

        return result;
    }

}
