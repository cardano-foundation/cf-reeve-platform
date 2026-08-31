package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.publish.PublishableEntity;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * Generic on-chain status watcher. Replaces the previously duplicated transaction / report status checks in
 * {@code WatchDogService}: it polls every {@link CardanoPublishable} type the same way, advancing finality and
 * triggering rollbacks via the shared {@link L1SubmissionDataUpdater}.
 */
@Service
@Slf4j
public class CardanoStatusWatcher {

    private final OrganisationPublicApiIF organisationPublicApi;
    private final BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    private final L1SubmissionDataUpdater l1SubmissionDataUpdater;
    private final TransactionTemplate transactionTemplate;

    public CardanoStatusWatcher(OrganisationPublicApiIF organisationPublicApi,
                                BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                L1SubmissionDataUpdater l1SubmissionDataUpdater,
                                PlatformTransactionManager transactionManager) {
        this.organisationPublicApi = organisationPublicApi;
        this.blockchainReaderPublicApi = blockchainReaderPublicApi;
        this.l1SubmissionDataUpdater = l1SubmissionDataUpdater;
        // One transaction per organisation, isolated from the others: a failure while processing one
        // organisation must not roll back the status updates already flushed for the organisations
        // processed before it in the same run.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <E extends PublishableEntity> void checkStatuses(CardanoPublishable<E> module, int limitPerOrg) {
        organisationPublicApi.listAll().forEach(org ->
                transactionTemplate.executeWithoutResult(status -> checkStatusesForOrganisation(module, org, limitPerOrg)));
    }

    private <E extends PublishableEntity> void checkStatusesForOrganisation(CardanoPublishable<E> module,
                                                                            Organisation org,
                                                                            int limitPerOrg) {
        ChainTip chainTip = getChainTip();
        if (!chainTip.isSynced()) {
            log.debug("Chain is not synced, skipping '{}' status check for organisation:{}", module.type(), org.getName());
            return;
        }

        Set<E> pending = module.findNotFinalizedYet(org.getId(), Limit.of(limitPerOrg));
        if (pending.isEmpty()) {
            log.debug("No '{}' found for status update for organisation:{}", module.type(), org.getName());
            return;
        }

        pending.forEach(entity -> {
            log.info("Checking '{}' status for entity:{}", module.type(), entity.getId());
            L1SubmissionData submissionData = entity.getL1SubmissionData().orElseThrow(() -> new RuntimeException("Failed to get L1 submission data"));
            entity.setL1SubmissionData(Optional.of(l1SubmissionDataUpdater.updateFromChain(submissionData, chainTip)));
        });

        module.storeAll(pending);

        log.info("Status updated for {} '{}' entities", pending.size(), module.type());
        module.notifyLedgerUpdate(org.getId(), pending);
    }

    private ChainTip getChainTip() {
        return blockchainReaderPublicApi.getChainTip().getOrElseThrow(() -> {
            log.error("Failed to get chain tip");
            return new RuntimeException("Failed to get chain tip");
        });
    }

}
