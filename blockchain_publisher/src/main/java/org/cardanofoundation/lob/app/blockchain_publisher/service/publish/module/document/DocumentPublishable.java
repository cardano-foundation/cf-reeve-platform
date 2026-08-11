package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;

/**
 * Publishable module for documents: each document becomes its own Cardano transaction, dispatched
 * under a lock so a slow IPFS upload or L1 submission cannot be picked up twice by overlapping
 * dispatcher ticks.
 */
@Service
@RequiredArgsConstructor
public class DocumentPublishable implements CardanoPublishable<DocumentEntity> {

    private final DocumentEntityRepositoryGateway repositoryGateway;
    private final DocumentL1TransactionCreator l1TransactionCreator;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Override
    public String type() {
        return "documents";
    }

    @Override
    public Set<DocumentEntity> findReadyToDispatch(String organisationId, int batchSize) {
        return repositoryGateway.findDocumentsReadyToBeDispatched(organisationId, batchSize);
    }

    @Override
    public Collection<Set<DocumentEntity>> groupForDispatch(Set<DocumentEntity> toDispatch) {
        // one Cardano transaction per document
        return toDispatch.stream()
                .<Set<DocumentEntity>>map(Set::of)
                .toList();
    }

    @Override
    public Either<ProblemDetail, Optional<L1Batch<DocumentEntity>>> buildL1Transaction(String organisationId, Set<DocumentEntity> unit) {
        DocumentEntity document = unit.iterator().next();

        return l1TransactionCreator.pullBlockchainTransaction(organisationId, document)
                .map(tx -> Optional.of(new L1Batch<>(
                        organisationId,
                        Set.of(document),
                        Set.of(),
                        tx.creationSlot(),
                        tx.serialisedTxData(),
                        tx.receiverAddress())));
    }

    @Override
    public Set<DocumentEntity> findNotFinalizedYet(String organisationId, Limit limit) {
        return repositoryGateway.findDispatchedDocumentsThatAreNotFinalizedYet(organisationId, limit);
    }

    @Override
    public void store(DocumentEntity entity) {
        repositoryGateway.store(entity);
    }

    @Override
    public void storeAll(Set<DocumentEntity> entities) {
        repositoryGateway.storeAll(entities);
    }

    @Override
    public boolean supportsLocking() {
        return true;
    }

    @Override
    public void lock(Set<DocumentEntity> entities) {
        repositoryGateway.lock(entities);
    }

    @Override
    public void unlock(Set<DocumentEntity> entities) {
        repositoryGateway.unlock(entities);
    }

    @Override
    public void notifyLedgerUpdate(String organisationId, Set<DocumentEntity> entities) {
        ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.DOCUMENT, entities,
                entity -> entity.getIpfsCid() == null
                        ? Set.of()
                        : Set.of(new BlockchainReceipt("IPFS", entity.getIpfsCid())));
    }

}
