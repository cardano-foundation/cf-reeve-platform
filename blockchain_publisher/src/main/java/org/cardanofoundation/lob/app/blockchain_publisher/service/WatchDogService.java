package org.cardanofoundation.lob.app.blockchain_publisher.service;

import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_common.domain.FinalityScore;
import org.cardanofoundation.lob.app.blockchain_common.domain.OnChainTxDetails;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.OnChainStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@RequiredArgsConstructor
@Service
@Slf4j
public class WatchDogService {

    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final BlockchainPublishStatusMapper blockchainPublishStatusMapper;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    private final ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Value("${lob.blockchain_publisher.watchdog.rollback.grace.period.minutes:15}")
    @Getter
    @Setter
    private int rollbackGracePeriodMinutes = 15;

    @PostConstruct
    public void init() {
        log.info("TransactionsWatchDogService configuration: rollbackGracePeriodMinutes={}, rollbacksEnabled={}", rollbackGracePeriodMinutes);

        log.info("TransactionsWatchDogService started");
    }

    public void checkReportStatusForOrganisations(int txStatusInspectionLimitPerOrgPullSize) {
        organisationPublicApiIF.listAll().forEach(org -> {
            log.info("Checking report statuses for organisation: {}", org.getName());
            checkReportStatusForOrganisation(org, txStatusInspectionLimitPerOrgPullSize);
        });
    }

    @Transactional
    public void checkTransactionStatusForOrganisations(int txStatusInspectionLimitPerOrgPullSize) {
        organisationPublicApiIF.listAll().forEach(org -> {
            log.info("Checking transaction statuses for organisation: {}", org.getName());
            checkTransactionStatusesForOrganisation(org, txStatusInspectionLimitPerOrgPullSize);
        });
    }

    private void checkReportStatusForOrganisation(Organisation org, int txStatusInspectionLimitPerOrgPullSize) {
        ChainTip chainTip = getChainTip();
        if (!chainTip.isSynced()) {
            log.info("Chain is not synced, skipping transaction status check for organisation: {}", org.getName());
            return;
        }

        Set<ReportEntity> reportEntities = reportEntityRepositoryGateway.findDispatchedReportsThatAreNotFinalizedYet(org.getId(), Limit.of(txStatusInspectionLimitPerOrgPullSize));

                reportEntities.forEach(report -> {
            log.info("Checking transaction status for report: {}", report.getId());
            L1SubmissionData l1SubmissionData = report.getL1SubmissionData().orElseThrow(() -> new RuntimeException("Failed to get L1 submission data"));
            report.setL1SubmissionData(Optional.of(updateL1SubmissionData(l1SubmissionData, chainTip)));

            reportEntityRepositoryGateway.storeReport(report);
            log.info("Status updated for report: {}", report.getId());
        });
        // notify accounting core about updated report
        ledgerUpdatedEventPublisher.sendReportLedgerUpdatedEvents(org.getId(), reportEntities);

    }

    private void checkTransactionStatusesForOrganisation(Organisation org, int txStatusInspectionLimitPerOrgPullSize) {
        ChainTip chainTip = getChainTip();
        if (!chainTip.isSynced()) {
            log.info("Chain is not synced, skipping transaction status check for organisation: {}", org.getName());
            return;
        }

        Set<TransactionEntity> successfullyUpdatedTxEntities = transactionEntityRepositoryGateway.findDispatchedTransactionsThatAreNotFinalizedYet(org.getId(), Limit.of(txStatusInspectionLimitPerOrgPullSize));
        successfullyUpdatedTxEntities.forEach(tx -> {
            log.info("Checking transaction status for transaction: {}", tx.getId());
            L1SubmissionData l1SubmissionData1 = tx.getL1SubmissionData().orElseThrow(() -> new RuntimeException("Failed to get L1 submission data"));
            tx.setL1SubmissionData(Optional.of(updateL1SubmissionData(l1SubmissionData1, chainTip)));

        });
        transactionEntityRepositoryGateway.storeTransactions(successfullyUpdatedTxEntities);

        log.info("Status updated for {} transactions", successfullyUpdatedTxEntities.size());
        // notify accounting core about updated transactions
        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(org.getId(), successfullyUpdatedTxEntities);

    }

    private OnChainStatus getOnChainStatus(Optional<OnChainTxDetails> onChainTxDetails, Long txCreationSlot, ChainTip chainTip) {
        if (onChainTxDetails.isPresent()) {
            return new OnChainStatus(blockchainPublishStatusMapper.convert(onChainTxDetails.get().getFinalityScore()), Optional.of(onChainTxDetails.get().getFinalityScore()));
        } else {
            // means tx is not on chain yet or was rolledback
            long txAgeInSlots = chainTip.getAbsoluteSlot() - txCreationSlot;
            // we have a grace period for rollback, this is to avoid premature rollbacks (e.g. when transaction is in the mempool still)
            boolean isRollbackReadyTimewise = txAgeInSlots > (rollbackGracePeriodMinutes * 60L);
            if (isRollbackReadyTimewise) {
                return new OnChainStatus(BlockchainPublishStatus.ROLLBACKED, Optional.empty());
            } else {
                return new OnChainStatus(BlockchainPublishStatus.SUBMITTED, Optional.of(FinalityScore.VERY_LOW));
            }
        }
    }

    private L1SubmissionData updateL1SubmissionData(L1SubmissionData submissionData, ChainTip chainTip) {
        Long txCreationSlot = submissionData.getCreationSlot().orElseThrow(() -> new RuntimeException("Failed to get tx creation slot"));
        String txHash = submissionData.getTransactionHash().orElseThrow(() -> new RuntimeException("Failed to get tx hash"));
        log.info("Checking transaction status changes for txHash:{}", txHash);
        Either<Problem, Optional<OnChainTxDetails>> txDetails = blockchainReaderPublicApi.getTxDetails(txHash);

        Optional<OnChainTxDetails> onChainTxDetails = txDetails.getOrElseThrow(() -> {
            log.error("Failed to get tx details for txHash:{}", txHash);
            return new RuntimeException("Failed to get tx details for txHash:" + txHash);
        });

        OnChainStatus onChainStatus = getOnChainStatus(onChainTxDetails, txCreationSlot, chainTip);

        if (onChainStatus.status().equals(BlockchainPublishStatus.ROLLBACKED)) {
            submissionData.setPublishStatus(BlockchainPublishStatus.ROLLBACKED);
            submissionData.setCreationSlot(null);
            submissionData.setAbsoluteSlot(null);
            submissionData.setTransactionHash(null);
            submissionData.setFinalityScore(null);
        } else {
            submissionData.setFinalityScore(onChainStatus.finalityScore().get());
            submissionData.setPublishStatus(onChainStatus.status());
        }
        return submissionData;
    }

    private ChainTip getChainTip() {
        return blockchainReaderPublicApi.getChainTip().getOrElseThrow(() -> {
            log.error("Failed to get chain tip");
            return new RuntimeException("Failed to get chain tip");
        });
    }
}
