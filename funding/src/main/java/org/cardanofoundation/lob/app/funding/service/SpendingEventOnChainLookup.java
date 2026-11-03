package org.cardanofoundation.lob.app.funding.service;

import java.util.Optional;

/**
 * Checks whether a spending event is already on-chain, independently of what the blockchain publisher reports.
 *
 * <p>Consulted before an event whose publishing failed is reverted to draft (LOB-2380): a failed attempt may still
 * have landed, and a reverted event can be edited and published again, which would put a second, different version
 * of it on-chain. Meant to be backed by the on-chain indexer (lookup by event id), the same way transactions are
 * reconciled against it; until then {@link NoopSpendingEventOnChainLookup} reports every event as not on-chain.
 */
public interface SpendingEventOnChainLookup {

    /**
     * @return the hash of the on-chain transaction carrying the event, or empty when it is not on-chain
     */
    Optional<String> findOnChainTxHash(String organisationId, String eventId);

}
