package org.cardanofoundation.lob.app.blockchain_publisher.domain.publish;

import java.util.Optional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;

/**
 * Common contract for anything the {@code blockchain_publisher} module publishes to Cardano L1
 * (transactions, reports, spending events, ...).
 *
 * <p>The generic publishing engine ({@code CardanoDispatcher} / {@code CardanoStatusWatcher}) only ever
 * sees entities through this interface, so adding a new publishable type does not require touching the
 * engine - only a new {@link org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable}
 * implementation.
 */
public interface PublishableEntity {

    String getId();

    String getOrganisationId();

    Optional<L1SubmissionData> getL1SubmissionData();

    void setL1SubmissionData(Optional<L1SubmissionData> l1SubmissionData);

}
