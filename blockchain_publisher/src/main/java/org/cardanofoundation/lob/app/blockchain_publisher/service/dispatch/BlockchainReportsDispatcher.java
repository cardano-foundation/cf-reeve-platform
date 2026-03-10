package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;
import org.apache.commons.lang3.tuple.Pair;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Service
@Slf4j
@RequiredArgsConstructor
public class BlockchainReportsDispatcher {

    private final OrganisationPublicApi organisationPublicApi;
    private final ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    private final DispatchingStrategy<ReportEntity> dispatchingStrategy = new ImmediateDispatchingStrategy<>();
    private final API3L1TransactionCreator api3L1TransactionCreator;
    private final TransactionSubmissionService transactionSubmissionService;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @Value("${lob.blockchain_publisher.dispatcher.pullBatchSize:50}")
    private int pullTransactionsBatchSize = 50;

    @Transactional
    public void dispatchReports() {
        for (Organisation organisation : organisationPublicApi.listAll()) {
            String organisationId = organisation.getId();

            Set<ReportEntity> reports = reportEntityRepositoryGateway.findReportsV2ByStatus(organisationId, pullTransactionsBatchSize);
            int reportsCount = reports.size();

            if (reportsCount > 0) {
                log.info("Dispatching reports for organisationId: {}, report count:{}", organisationId, reportsCount);
                Set<ReportEntity> toDispatch = dispatchingStrategy.apply(organisationId, reports);

                dispatchReports(organisationId, toDispatch);
            } else {
                log.debug("No pending reports to dispatch for organisationId: {}", organisationId);
            }
        }
    }

    @Transactional
    public void dispatchReports(String organisationId,
                                Set<ReportEntity> reportEntities) {
        for (ReportEntity reportEntity : reportEntities) {
            dispatchReport(organisationId, reportEntity);
        }
    }

    @Transactional
    public void dispatchReport(String organisationId, ReportEntity reportEntity) {
        Either<ProblemDetail, API3BlockchainTransaction> api3BlockchainTransactionE = createAndSendBlockchainTransactions(reportEntity);
        if (api3BlockchainTransactionE.isEmpty()) {
            L1SubmissionData l1SubmissionData = L1SubmissionData.builder()
                    .publishRetry(reportEntity.getL1SubmissionData().map(L1SubmissionData::getPublishRetry).orElse(0L) + 1L)
                    .publishStatusErrorReason(Objects.requireNonNull(api3BlockchainTransactionE.getLeft().getDetail()))
                    .publishStatus(reportEntity.getL1SubmissionData().map(L1SubmissionData::getPublishRetry).orElse(0L) >= 5L ? BlockchainPublishStatus.ERROR : BlockchainPublishStatus.STORED)
                    .build();
            reportEntity.setL1SubmissionData(Optional.of(l1SubmissionData));
            reportEntityRepositoryGateway.storeReport(reportEntity);
            ledgerUpdatedEventPublisher.sendReportLedgerUpdatedEvents(organisationId, Set.of(
                    Pair.of(reportEntity.getId(), l1SubmissionData)
            ));
        }
    }

    @Transactional
    public Either<ProblemDetail, API3BlockchainTransaction> createAndSendBlockchainTransactions(ReportEntity reportEntity) {
        log.info("Creating and sending blockchain transactions for report:{}", reportEntity.getId());

        Either<ProblemDetail, API3BlockchainTransaction> serialisedTxE = api3L1TransactionCreator.pullBlockchainTransaction(reportEntity);

        if (serialisedTxE.isLeft()) {
            ProblemDetail problem = serialisedTxE.getLeft();

            log.error("Error pulling blockchain transaction, problem: {}", problem);

            return serialisedTxE;
        }

        API3BlockchainTransaction serialisedTx = serialisedTxE.get();
        try {
            sendTransactionOnChainAndUpdateDb(reportEntity, serialisedTx);

            return serialisedTxE;
        } catch (ApiException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "%s".formatted(e.getMessage()));
            problem.setTitle("ERROR_PUSHING_TRANSACTION");
            return Either.left(problem);
        }

    }

    @Transactional
    public void sendTransactionOnChainAndUpdateDb(ReportEntity report, API3BlockchainTransaction api3BlockchainTransaction) throws ApiException {
        byte[] reportTxData = api3BlockchainTransaction.serialisedTxData();

        L1Submission l1SubmissionData = transactionSubmissionService.submitTransactionWithPossibleConfirmation(reportTxData, api3BlockchainTransaction.receiverAddress());

        String txHash = l1SubmissionData.txHash();
        Optional<Long> txAbsoluteSlotM = l1SubmissionData.absoluteSlot();

        long creationSlot = api3BlockchainTransaction.creationSlot();

        updateTransactionStatuses(txHash, txAbsoluteSlotM, creationSlot, report);

        ledgerUpdatedEventPublisher.sendReportLedgerUpdatedEvents(report.getOrganisationId(), Set.of(
                Pair.of(report.getId(), report.getL1SubmissionData().get())
        ));

        log.info("Blockchain transaction submitted (report), l1SubmissionData:{}", l1SubmissionData);
    }

    @Transactional
    public void updateTransactionStatuses(String txHash,
                                             Optional<Long> absoluteSlot,
                                             long creationSlot,
                                             ReportEntity reportEntity) {

        reportEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                    .transactionHash(txHash)
                    .absoluteSlot(absoluteSlot.orElse(null)) // if tx is not confirmed yet, slot will not be available
                    .creationSlot(creationSlot)
                    .publishStatus(BlockchainPublishStatus.SUBMITTED)
                    .build()
            ));

        reportEntityRepositoryGateway.storeReport(reportEntity);
    }
}
