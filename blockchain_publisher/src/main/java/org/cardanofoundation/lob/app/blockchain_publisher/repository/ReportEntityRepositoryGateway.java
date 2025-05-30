package org.cardanofoundation.lob.app.blockchain_publisher.repository;

import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
@Transactional(readOnly = true)
public class ReportEntityRepositoryGateway {

    private final ReportEntityRepository reportEntityRepository;

    @Transactional
    public Set<ReportEntity> storeOnlyNew(Set<ReportEntity> reportEntities) {
        log.info("Store only new..., store only new: {}", reportEntities.size());

        Set<String> reportIds = reportEntities.stream()
                .map(ReportEntity::getId)
                .collect(toSet());

        Set<String> existingReportIds = new HashSet<>(reportEntityRepository
                .findAllById(reportIds))
                .stream().map(ReportEntity::getId).collect(toSet());

        Set<ReportEntity> newReports = reportEntities.stream()
                .filter(report -> !existingReportIds.contains(report.getId()))
                .collect(Collectors.toSet());

        return new HashSet<>(reportEntityRepository.saveAll(newReports));
    }

    public Set<ReportEntity> findDispatchedReportsThatAreNotFinalizedYet(String organisationId, Limit limit) {
        Set<BlockchainPublishStatus> notFinalisedButVisibleOnChain = BlockchainPublishStatus.notFinalisedButVisibleOnChain();

        return reportEntityRepository.findDispatchedReportsThatAreNotFinalizedYet(organisationId, notFinalisedButVisibleOnChain, limit);
    }

    @Transactional
    public Set<ReportEntity> findReportsByStatus(String organisationId,
                                                 int pullReportsBatchSize) {
        Set<BlockchainPublishStatus> dispatchStatuses = BlockchainPublishStatus.toDispatchStatuses();
        Limit limit = Limit.of(pullReportsBatchSize);

        return reportEntityRepository.findReportsByStatus(organisationId, dispatchStatuses, limit);
    }

    @Transactional
    public void storeReport(ReportEntity reportEntity) {
        reportEntityRepository.save(reportEntity);
    }

}
