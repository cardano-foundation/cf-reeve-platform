package org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish;

import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublishStatusMapper;
import org.cardanofoundation.lob.app.support.collections.Partitions;

/**
 * Publishes ledger-update events back to the originating modules. The whole "read each entity's
 * {@link L1SubmissionData} and turn it into a status update" logic lives here once and works for any
 * {@link PublishableEntity} (transactions, reports, spending events) - the caller only supplies the
 * {@link LedgerUpdateType} that lets each consumer pick out the updates relevant to it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerUpdatedEventPublisher {

    public static final String BLOCKCHAIN_TYPE = "CARDANO_L1";

    private final ApplicationEventPublisher applicationEventPublisher;
    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;

    @Value("${lob.blockchain_publisher.send.batch.size:100}")
    protected int dispatchBatchSize = 100;

    @Transactional
    public <E extends PublishableEntity> void send(String organisationId,
                                                   LedgerUpdateType type,
                                                   Set<E> entities) {
        if (entities.isEmpty()) {
            return;
        }

        log.info("Sending {} ledger updated event for organisation:{}, count:{}", type, organisationId, entities.size());

        for (val partition : Partitions.partition(entities, dispatchBatchSize)) {
            val statusUpdates = partition.asSet().stream()
                    .map(this::toStatusUpdate)
                    .collect(Collectors.toSet());

            val event = LedgerUpdatedEvent.builder()
                    .organisationId(organisationId)
                    .type(type)
                    .statusUpdates(statusUpdates)
                    .build();

            log.info("Sending {} ledger updated event for organisation:{}, statuses:{}", type, organisationId, statusUpdates);

            applicationEventPublisher.publishEvent(event);
        }
    }

    private LedgerStatusUpdate toStatusUpdate(PublishableEntity entity) {
        val l1SubmissionData = entity.getL1SubmissionData();

        val ledgerDispatchStatus = blockchainPublishStatusMapper.convert(
                l1SubmissionData.flatMap(L1SubmissionData::getPublishStatus),
                l1SubmissionData.flatMap(L1SubmissionData::getFinalityScore));

        val blockchainHash = l1SubmissionData.flatMap(L1SubmissionData::getTransactionHash).orElse(null);
        val errorReason = l1SubmissionData.flatMap(L1SubmissionData::getPublishStatusErrorReason).orElse(null);

        return new LedgerStatusUpdate(
                entity.getId(),
                ledgerDispatchStatus,
                errorReason,
                Set.of(new BlockchainReceipt(BLOCKCHAIN_TYPE, blockchainHash)));
    }

}
