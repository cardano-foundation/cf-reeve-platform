package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.notFinalisedButVisibleOnChain;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Sets;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DocumentEntityRepositoryGateway {

    private final DocumentEntityRepository documentEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}")
    private Duration lockTimeoutDuration;

    /**
     * Atomically claims documents ready for dispatch: the locking read (FOR UPDATE SKIP LOCKED) and the
     * lockedAt write commit together, before this method returns (REQUIRES_NEW), so a concurrent dispatcher
     * instance can never claim the same rows. Returns the claimed ids in dispatch order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<String> claimDocumentsReadyToBeDispatched(String organisationId,
            int pullBatchSize,
            DispatchingStrategy<DocumentEntity> dispatchingStrategy) {
        Set<DocumentEntity> free = documentEntityRepository.findFreeByStatus(
                organisationId,
                BlockchainPublishStatus.toDispatchStatuses(),
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullBatchSize));

        Set<DocumentEntity> toDispatch = dispatchingStrategy.apply(organisationId, free);

        LocalDateTime now = LocalDateTime.now(clock);
        toDispatch.forEach(entity -> entity.setLockedAt(now));

        return toDispatch.stream()
                .map(DocumentEntity::getId)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<DocumentEntity> findAllByIdsPreservingOrder(Set<String> ids) {
        Map<String, DocumentEntity> byId = documentEntityRepository.findAllById(ids).stream()
                .collect(toMap(DocumentEntity::getId, identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<DocumentEntity> findDispatchedDocumentsThatAreNotFinalizedYet(String organisationId, Limit limit) {
        return documentEntityRepository.findDispatchedThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain(), limit);
    }

    /**
     * Store only new documents so re-delivery of the same {@code DocumentPublishCommand} is idempotent.
     */
    @Transactional
    public Set<DocumentEntity> storeOnlyNew(Set<DocumentEntity> entities) {
        Set<String> ids = entities.stream().map(DocumentEntity::getId).collect(toSet());

        Set<DocumentEntity> existing = new HashSet<>(documentEntityRepository.findAllById(ids));
        Sets.SetView<DocumentEntity> newEntities = Sets.difference(entities, existing);

        return Stream.concat(documentEntityRepository.saveAll(newEntities).stream(), existing.stream())
                .collect(toSet());
    }

    @Transactional
    public void store(DocumentEntity entity) {
        documentEntityRepository.save(entity);
    }

    @Transactional
    public void storeAll(Set<DocumentEntity> entities) {
        documentEntityRepository.saveAll(entities);
    }

    @Transactional
    public void unlock(Set<DocumentEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(null));
        documentEntityRepository.saveAll(batch);
    }

}
