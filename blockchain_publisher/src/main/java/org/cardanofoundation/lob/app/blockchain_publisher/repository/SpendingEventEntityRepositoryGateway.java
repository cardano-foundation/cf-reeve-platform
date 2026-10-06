package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.notFinalisedButVisibleOnChain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Sets;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SpendingEventEntityRepositoryGateway {

    private final SpendingEventEntityRepository spendingEventEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}")
    private Duration lockTimeoutDuration;

    public Set<SpendingEventEntity> findEventsReadyToBeDispatched(String organisationId, int pullBatchSize) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();

        return spendingEventEntityRepository.findFreeByStatus(
                organisationId,
                dispatchStatuses,
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullBatchSize));
    }

    public Set<SpendingEventEntity> findDispatchedEventsThatAreNotFinalizedYet(String organisationId, Limit limit) {
        return spendingEventEntityRepository.findDispatchedThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain(), limit);
    }

    /**
     * Store only new spending events so re-delivery of the same event is idempotent. The one exception is an event
     * that failed for good ({@code ERROR}) and is published again: its stored copy is replaced by the incoming one -
     * the event may have been corrected in the meantime - and it starts over from {@code STORED}. Its publish attempt
     * history lives in a separate table and is kept.
     */
    @Transactional
    public Set<SpendingEventEntity> storeOnlyNew(Set<SpendingEventEntity> entities) {
        Set<String> ids = entities.stream().map(SpendingEventEntity::getId).collect(toSet());

        Set<SpendingEventEntity> existing = new HashSet<>(spendingEventEntityRepository.findAllById(ids));
        Set<SpendingEventEntity> failed = existing.stream()
                .filter(SpendingEventEntityRepositoryGateway::isFailed)
                .collect(toSet());
        if (!failed.isEmpty()) {
            log.info("Replacing {} failed spending event(s) published again: {}", failed.size(), failed.stream().map(SpendingEventEntity::getId).toList());
            spendingEventEntityRepository.deleteAll(failed);
            // the replacements reuse the same ids, so the deletes must reach the database before the inserts
            spendingEventEntityRepository.flush();
            existing.removeAll(failed);
        }

        Sets.SetView<SpendingEventEntity> newEntities = Sets.difference(entities, existing);

        return Stream.concat(spendingEventEntityRepository.saveAll(newEntities).stream(), existing.stream())
                .collect(toSet());
    }

    private static boolean isFailed(SpendingEventEntity entity) {
        return entity.getL1SubmissionData()
                .flatMap(L1SubmissionData::getPublishStatus)
                .filter(BlockchainPublishStatus.ERROR::equals)
                .isPresent();
    }

    @Transactional
    public void store(SpendingEventEntity entity) {
        spendingEventEntityRepository.save(entity);
    }

    @Transactional
    public void storeAll(Set<SpendingEventEntity> entities) {
        spendingEventEntityRepository.saveAll(entities);
    }

    @Transactional
    public void lock(Set<SpendingEventEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(LocalDateTime.now(clock)));
        spendingEventEntityRepository.saveAll(batch);
    }

    @Transactional
    public void unlock(Set<SpendingEventEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(null));
        spendingEventEntityRepository.saveAll(batch);
    }

}
