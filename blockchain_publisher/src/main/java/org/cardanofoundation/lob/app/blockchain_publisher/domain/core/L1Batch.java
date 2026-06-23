package org.cardanofoundation.lob.app.blockchain_publisher.domain.core;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;

/**
 * Normalised result of building a single Cardano L1 transaction for one dispatch unit, regardless of the
 * publishable type (API1 batched transactions, API3 single reports, spending events, ...).
 *
 * @param submitted        entities that made it into this Cardano transaction
 * @param remaining        entities deferred to a later run (e.g. API1 metadata size limit); empty for one-per-tx types
 * @param creationSlot     slot at which the transaction was built
 * @param serialisedTxData CBOR serialised, signed transaction ready for submission
 * @param receiverAddress  address the transaction is sent to
 */
public record L1Batch<E extends PublishableEntity>(String organisationId,
                                                   Set<E> submitted,
                                                   Set<E> remaining,
                                                   long creationSlot,
                                                   byte[] serialisedTxData,
                                                   String receiverAddress) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        L1Batch<?> that = (L1Batch<?>) o;

        return creationSlot == that.creationSlot
                && Objects.equals(organisationId, that.organisationId)
                && Objects.equals(submitted, that.submitted)
                && Objects.equals(remaining, that.remaining)
                && Arrays.equals(serialisedTxData, that.serialisedTxData)
                && Objects.equals(receiverAddress, that.receiverAddress);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(organisationId, submitted, remaining, creationSlot, receiverAddress);
        result = 31 * result + Arrays.hashCode(serialisedTxData);

        return result;
    }

}
