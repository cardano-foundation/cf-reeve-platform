package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch.DispatchingStrategy;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportEntityRepositoryGateway {

    private final ReportEntityRepository reportEntityRepository;
    private final Clock clock;

    @Value("${lob.blockchain_publisher.dispatcher.lock_timeout:PT3H}")
    private Duration lockTimeoutDuration;

    public Set<ReportEntity> findDispatchedReportsV2ThatAreNotFinalizedYet(String organisationId, Limit limit) {
        Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain = BlockchainPublishStatus.notFinalisedButVisibleOnChain();

        return reportEntityRepository.findDispatchedReportsThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain, limit);
    }

    @Transactional
    public void storeReport(ReportEntity reportEntity) {
        reportEntityRepository.save(reportEntity);
    }

    @Transactional
    public void storeReportV2IfNew(ReportEntity reportEntity) {
        Optional<ReportEntity> existing = reportEntityRepository.findById(reportEntity.getId());
        if (existing.isEmpty()) {
            reportEntityRepository.save(reportEntity);
        } else {
            ReportEntity existingEntity = existing.get();
            boolean notYetSubmitted = existingEntity.getL1SubmissionData()
                    .flatMap(l1 -> l1.getPublishStatus())
                    .map(status -> status == BlockchainPublishStatus.STORED)
                    .orElse(true);
            if (notYetSubmitted) {
                existingEntity.setReportData(reportEntity.getReportData());
            }
        }
    }

    /**
     * Atomically claims reports ready for dispatch: the locking read (FOR UPDATE SKIP LOCKED) and the
     * lockedAt write commit together, before this method returns (REQUIRES_NEW), so a concurrent dispatcher
     * instance can never claim the same rows. Returns the claimed ids in dispatch order.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<String> claimReportsReadyToBeDispatched(String organisationId,
                                                       int pullReportsBatchSize,
                                                       DispatchingStrategy<ReportEntity> dispatchingStrategy) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();

        Set<ReportEntity> free = reportEntityRepository.findFreeReportsByStatus(
                organisationId,
                dispatchStatuses,
                LocalDateTime.now(clock).minus(lockTimeoutDuration),
                Limit.of(pullReportsBatchSize));

        Set<ReportEntity> toDispatch = dispatchingStrategy.apply(organisationId, free);

        LocalDateTime now = LocalDateTime.now(clock);
        toDispatch.forEach(report -> report.setLockedAt(now));

        return toDispatch.stream()
                .map(ReportEntity::getId)
                .collect(toCollection(LinkedHashSet::new));
    }

    public Set<ReportEntity> findAllByIdsPreservingOrder(Set<String> ids) {
        Map<String, ReportEntity> byId = reportEntityRepository.findAllById(ids).stream()
                .collect(toMap(ReportEntity::getId, identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(toCollection(LinkedHashSet::new));
    }

    @Transactional
    public void unlockReports(Set<ReportEntity> batch) {
        batch.forEach(report -> report.setLockedAt(null));
        reportEntityRepository.saveAll(batch);
    }

}
