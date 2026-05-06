package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportEntityRepositoryGateway {

    private final ReportEntityRepository reportEntityRepository;

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

    @Transactional
    public Set<ReportEntity> findReportsV2ByStatus(String organisationId, int pullReportsBatchSize) {
        Set<BlockchainPublishStatus> dispatchStatuses =
                BlockchainPublishStatus.toDispatchStatuses();
        Limit limit = Limit.of(pullReportsBatchSize);

        return reportEntityRepository.findReportsByStatus(organisationId, dispatchStatuses, limit);
    }

}
