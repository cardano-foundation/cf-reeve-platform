package org.cardanofoundation.lob.app.funding.service;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Placeholder until the on-chain indexer can be queried by event id: reports every event as not on-chain, i.e. a
 * failed event is always reverted to draft - the same trust in the publisher's verdict that transactions and reports
 * get today. Replace with an indexer-backed implementation.
 */
@Service
@Slf4j
public class NoopSpendingEventOnChainLookup implements SpendingEventOnChainLookup {

    @Override
    public Optional<String> findOnChainTxHash(String organisationId, String eventId) {
        log.debug("On-chain lookup not available yet, assuming event {} is not on-chain", eventId);

        return Optional.empty();
    }

}
