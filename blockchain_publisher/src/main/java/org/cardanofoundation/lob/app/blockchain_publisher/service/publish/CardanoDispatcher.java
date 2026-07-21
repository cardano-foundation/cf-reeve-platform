package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus.SUBMITTED;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.blockchain_common.BlockchainException;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * Generic dispatcher that publishes any {@link CardanoPublishable} type to Cardano L1. Replaces the previously
 * duplicated {@code BlockchainTransactionsDispatcher} and {@code BlockchainReportsDispatcher}: the whole
 * fetch -> lock -> build -> submit -> update -> notify pipeline lives here once, and per-type behaviour is
 * supplied entirely by the injected module.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CardanoDispatcher {

    private final OrganisationPublicApi organisationPublicApi;
    private final TransactionSubmissionService transactionSubmissionService;
    private final L1SubmissionDataUpdater l1SubmissionDataUpdater;

    @Value("${lob.blockchain_publisher.dispatcher.pullBatchSize:50}")
    private int pullBatchSize = 50;

    @Transactional
    public <E extends PublishableEntity> void dispatch(CardanoPublishable<E> module) {
        log.debug("Dispatching '{}' to the cardano blockchain...", module.type());

        for (Organisation organisation : organisationPublicApi.listAll()) {
            String organisationId = organisation.getId();

            Set<E> ready = module.findReadyToDispatch(organisationId, pullBatchSize);
            Set<E> toDispatch = module.dispatchingStrategy().apply(organisationId, ready);

            if (module.supportsLocking()) {
                Set<E> toUnlock = new HashSet<>(ready);
                toUnlock.removeAll(toDispatch);
                module.unlock(toUnlock);
            }

            if (toDispatch.isEmpty()) {
                log.debug("No pending '{}' to dispatch for organisationId:{}", module.type(), organisationId);
                continue;
            }

            log.info("Dispatching '{}' for organisationId:{}, count:{}", module.type(), organisationId, toDispatch.size());
            for (Set<E> unit : module.groupForDispatch(toDispatch)) {
                if (!unit.isEmpty()) {
                    dispatchUnit(module, organisationId, unit);
                }
            }
        }
    }

    private <E extends PublishableEntity> void dispatchUnit(CardanoPublishable<E> module,
                                                            String organisationId,
                                                            Set<E> unit) {
        if (module.supportsLocking()) {
            module.lock(unit);
        }

        try {
            Either<ProblemDetail, Optional<L1Batch<E>>> builtE = module.buildL1Transaction(organisationId, unit);

            if (builtE.isLeft() || builtE.get().isEmpty()) {
                String reason = builtE.isLeft() ? builtE.getLeft().getDetail() : "Empty response";
                log.warn("Could not build L1 transaction for '{}', organisationId:{}, reason:{}", module.type(), organisationId, reason);
                applyFailure(module, organisationId, unit, reason);
                return;
            }

            L1Batch<E> batch = builtE.get().orElseThrow();
            submitAndUpdate(module, organisationId, batch);
        } catch (ApiException | BlockchainException e) {
            log.error("Error sending '{}' on chain and / or updating db", module.type(), e);
            applyFailure(module, organisationId, unit, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while sending '{}' on chain and / or updating db", module.type(), e);
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, e.getMessage());
            applyFailure(module, organisationId, unit, problemDetail.getDetail());
        } finally {
            if (module.supportsLocking()) {
                module.unlock(unit);
            }
        }
    }

    private <E extends PublishableEntity> void submitAndUpdate(CardanoPublishable<E> module,
                                                               String organisationId,
                                                               L1Batch<E> batch) throws ApiException {
        L1Submission l1Submission = transactionSubmissionService.submitTransactionWithPossibleConfirmation(batch.serialisedTxData(), batch.receiverAddress());

        String txHash = l1Submission.txHash();
        Optional<Long> absoluteSlot = l1Submission.absoluteSlot();

        for (E entity : batch.submitted()) {
            entity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                    .transactionHash(txHash)
                    .absoluteSlot(absoluteSlot.orElse(null)) // if tx is not confirmed yet, slot will not be available
                    .creationSlot(batch.creationSlot())
                    .publishStatus(SUBMITTED)
                    .build()));
            module.store(entity);
        }

        module.notifyLedgerUpdate(organisationId, batch.submitted());

        log.info("'{}' submitted to blockchain, l1Submission:{}", module.type(), l1Submission);
    }

    private <E extends PublishableEntity> void applyFailure(CardanoPublishable<E> module,
                                                            String organisationId,
                                                            Set<E> unit,
                                                            String reason) {
        for (E entity : unit) {
            entity.setL1SubmissionData(Optional.of(l1SubmissionDataUpdater.applyFailure(entity.getL1SubmissionData(), reason)));
            module.store(entity);
        }
        module.notifyLedgerUpdate(organisationId, unit);
    }

}
