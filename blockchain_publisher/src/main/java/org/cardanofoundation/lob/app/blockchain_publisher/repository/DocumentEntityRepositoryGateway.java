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
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DocumentEntityRepositoryGateway {

    private final DocumentEntityRepository documentEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}")
    private Duration lockTimeoutDuration;

    public Set<DocumentEntity> findDocumentsReadyToBeDispatched(String organisationId, int pullBatchSize) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();

        return documentEntityRepository.findFreeByStatus(
                organisationId,
                dispatchStatuses,
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullBatchSize));
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
    public void lock(Set<DocumentEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(LocalDateTime.now(clock)));
        documentEntityRepository.saveAll(batch);
    }

    @Transactional
    public void unlock(Set<DocumentEntity> batch) {
        batch.forEach(entity -> entity.setLockedAt(null));
        documentEntityRepository.saveAll(batch);
    }

}
