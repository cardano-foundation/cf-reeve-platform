package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.SUBMITTED;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.blockchain_common.BlockchainException;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API1BlockchainTransactions;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Service
@Slf4j
@RequiredArgsConstructor
public class BlockchainTransactionsDispatcher {

    private final TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    private final OrganisationPublicApi organisationPublicApi;
    private final API1L1TransactionCreator l1TransactionCreator;
    private final TransactionSubmissionService transactionSubmissionService;
    private final LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    private final DispatchingStrategy<TransactionEntity> dispatchingStrategy;

    @Value("${lob.blockchain_publisher.dispatcher.pullBatchSize:500}")
    private int pullTransactionsBatchSize = 50;

    @PostConstruct
    public void init() {
        log.info("BlockchainTransactionsDispatcher initialized with pullTransactionsBatchSize:{}", pullTransactionsBatchSize);
        log.info("DispatchStrategy:{}", dispatchingStrategy.getClass().getSimpleName());
    }

    @Transactional
    public void dispatchTransactions() {
        log.info("Dispatching txs to the cardano blockchain...");

        for (Organisation organisation : organisationPublicApi.listAll()) {
            String organisationId = organisation.getId();
            Set<TransactionEntity> transactionsBatch = transactionEntityRepositoryGateway.findAndLockTransactionsReadyToBeDispatched(organisationId, pullTransactionsBatchSize);
            Set<TransactionEntity> transactionToDispatch = dispatchingStrategy.apply(organisationId, transactionsBatch);
            // unlock other transactions
            HashSet<TransactionEntity> toUnlock = new HashSet<>(transactionsBatch);
            toUnlock.removeAll(transactionToDispatch);
            transactionEntityRepositoryGateway.unlockTransactions(toUnlock);
            int dispatchTxCount = transactionToDispatch.size();
            log.info("Dispatching txs for organisationId:{}, tx count:{}", organisationId, dispatchTxCount);
            if (dispatchTxCount > 0) {
                dispatchTransactionsBatch(organisationId, transactionToDispatch);
            }
        }
    }

    private void dispatchTransactionsBatch(String organisationId,
                                          Set<TransactionEntity> transactionEntitiesBatch) {
        log.info("Dispatching passedTransactions for organisation: {}", organisationId);

        Optional<API1BlockchainTransactions> blockchainTransactionsM = createAndSendBlockchainTransactions(organisationId, transactionEntitiesBatch);

        if (blockchainTransactionsM.isEmpty()) {
            log.info("No more passedTransactions to dispatch for organisationId: {}", organisationId);
            return;
        }

        API1BlockchainTransactions blockchainTransactions = blockchainTransactionsM.orElseThrow();

        int submittedTxCount = blockchainTransactions.submittedTransactions().size();
        int remainingTxCount = blockchainTransactions.remainingTransactions().size();
        transactionEntityRepositoryGateway.unlockTransactions(blockchainTransactions.remainingTransactions());
        transactionEntityRepositoryGateway.unlockTransactions(blockchainTransactions.submittedTransactions());
        log.info("Submitted tx count:{}, remainingTxCount:{}", submittedTxCount, remainingTxCount);
    }

    private Optional<API1BlockchainTransactions> createAndSendBlockchainTransactions(String organisationId,
                                                                                     Set<TransactionEntity> transactions) {
        log.info("Processing passedTransactions for organisation:{}, remaining size:{}", organisationId, transactions.size());

        if (transactions.isEmpty()) {
            log.info("No more passedTransactions to dispatch for organisation:{}", organisationId);

            return Optional.empty();
        }

        Either<Problem, Optional<API1BlockchainTransactions>> serialisedTxE = l1TransactionCreator.pullBlockchainTransaction(organisationId, transactions);
        if (serialisedTxE.isEmpty()) {
            log.warn("Error, there is more passedTransactions to dispatch for organisation:{}, actual issue:{}", organisationId, serialisedTxE.getLeft());

            return Optional.empty();
        }

        Optional<API1BlockchainTransactions> serialisedTxM = serialisedTxE.get();

        if (serialisedTxM.isEmpty()) {
            log.warn("No passedTransactions to dispatch for organisationId:{}", organisationId);

            return Optional.empty();
        }

        API1BlockchainTransactions serialisedTx = serialisedTxM.orElseThrow();
        try {
            sendTransactionOnChainAndUpdateDb(serialisedTx);

            return Optional.of(serialisedTx);
        } catch (ApiException | BlockchainException e) {
            log.error("Error sending transaction on chain and / or updating db", e);
        } catch (Exception e) {
            log.error("Unexpected error while sending transaction on chain and / or updating db", e);
        }

        return Optional.empty();
    }

    private void sendTransactionOnChainAndUpdateDb(API1BlockchainTransactions blockchainTransactions) throws ApiException, InterruptedException {
        byte[] txData = blockchainTransactions.serialisedTxData();
        L1Submission l1SubmissionData = transactionSubmissionService.submitTransactionWithPossibleConfirmation(txData, blockchainTransactions.receiverAddress());
        String organisationId = blockchainTransactions.organisationId();
        Set<TransactionEntity> allTxs = blockchainTransactions.submittedTransactions();

        String txHash = l1SubmissionData.txHash();
        Optional<Long> txAbsoluteSlotM = l1SubmissionData.absoluteSlot();

        updateTransactionStatuses(txHash, txAbsoluteSlotM, blockchainTransactions);

        ledgerUpdatedEventPublisher.sendTxLedgerUpdatedEvents(organisationId, allTxs);

        log.info("Blockchain transaction submitted, l1SubmissionData:{}", l1SubmissionData);
    }

    private void updateTransactionStatuses(String txHash,
                                           Optional<Long> absoluteSlot,
                                           API1BlockchainTransactions blockchainTransactions) {
        for (TransactionEntity txEntity : blockchainTransactions.submittedTransactions()) {
            txEntity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                    .transactionHash(txHash)
                    .absoluteSlot(absoluteSlot.orElse(null)) // if tx is not confirmed yet, slot will not be available
                    .creationSlot(blockchainTransactions.creationSlot())
                    .publishStatus(SUBMITTED)
                    .build())
            );

            transactionEntityRepositoryGateway.storeTransaction(txEntity);
        }
    }

}
