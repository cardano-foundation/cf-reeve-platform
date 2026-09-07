package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Limit;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.CardanoPublishable;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3L1TransactionCreator;

/**
 * Publishable module for reports (API3): each report becomes its own Cardano transaction, claimed atomically
 * under the same locking window as the other publishable types.
 */
@Service
@RequiredArgsConstructor
public class ReportPublishable implements CardanoPublishable<ReportEntity> {

    private final ReportEntityRepositoryGateway repositoryGateway;
    private final API3L1TransactionCreator l1TransactionCreator;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Override
    public String type() {
        return "reports";
    }

    @Override
    public Set<String> claimReadyToDispatch(String organisationId, int batchSize) {
        return repositoryGateway.claimReportsReadyToBeDispatched(organisationId, batchSize, dispatchingStrategy());
    }

    @Override
    public Set<ReportEntity> loadByIds(Set<String> ids) {
        return repositoryGateway.findAllByIdsPreservingOrder(ids);
    }

    @Override
    public void unlock(Set<ReportEntity> entities) {
        repositoryGateway.unlockReports(entities);
    }

    @Override
    public Collection<Set<ReportEntity>> groupForDispatch(Set<ReportEntity> toDispatch) {
        // one Cardano transaction per report
        return toDispatch.stream()
                .<Set<ReportEntity>>map(Set::of)
                .toList();
    }

    @Override
    public Either<ProblemDetail, Optional<L1Batch<ReportEntity>>> buildL1Transaction(String organisationId, Set<ReportEntity> unit) {
        ReportEntity report = unit.iterator().next();

        return l1TransactionCreator.pullBlockchainTransaction(report)
                .map(api3 -> Optional.of(new L1Batch<>(
                        report.getOrganisationId(),
                        Set.of(report),
                        Set.of(),
                        api3.creationSlot(),
                        api3.serialisedTxData(),
                        api3.receiverAddress())));
    }

    @Override
    public Set<ReportEntity> findNotFinalizedYet(String organisationId, Limit limit) {
        return repositoryGateway.findDispatchedReportsV2ThatAreNotFinalizedYet(organisationId, limit);
    }

    @Override
    public void store(ReportEntity entity) {
        repositoryGateway.storeReport(entity);
    }

    @Override
    public void storeAll(Set<ReportEntity> entities) {
        entities.forEach(repositoryGateway::storeReport);
    }

    @Override
    public void notifyLedgerUpdate(String organisationId, Set<ReportEntity> entities) {
        ledgerUpdatedEventPublisher.send(organisationId, LedgerUpdateType.REPORT, entities);
    }

}
