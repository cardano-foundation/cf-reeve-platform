package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent;

import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.AbstractL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.L1TransactionCreatorConfig;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

/**
 * L1 transaction creator for spending events. Reuses the batched chunking / signing pipeline from
 * {@link AbstractL1TransactionCreator}; the only spending-specific part is metadata serialisation, which is
 * delegated to {@link SpendingEventMetadataSerialiser} (not implemented yet - the metadata schema is being
 * populated). Until then any dispatch attempt is short-circuited to a {@link org.springframework.http.ProblemDetail}
 * via the base's error handling, leaving events in {@code STORED} state.
 */
@Slf4j
public class SpendingEventL1TransactionCreator extends AbstractL1TransactionCreator<SpendingEventEntity> {

    private final SpendingEventMetadataSerialiser spendingEventMetadataSerialiser;

    public SpendingEventL1TransactionCreator(BackendService backendService,
                                             SpendingEventMetadataSerialiser spendingEventMetadataSerialiser,
                                             BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                             MetadataChecker jsonSchemaMetadataChecker,
                                             Account organiserAccount,
                                             Optional<IpfsPublisher> ipfsPublisher,
                                             L1TransactionCreatorConfig config) {
        super(backendService, blockchainReaderPublicApi, jsonSchemaMetadataChecker, organiserAccount, ipfsPublisher, config);
        this.spendingEventMetadataSerialiser = spendingEventMetadataSerialiser;
    }

    @Override
    protected MetadataMap serialiseToMetadataMap(String organisationId, Set<SpendingEventEntity> batch, long creationSlot) {
        return spendingEventMetadataSerialiser.serialiseToMetadataMap(batch, creationSlot);
    }

    @Override
    protected String metadataFilePrefix() {
        return "lob-spending-events-metadata";
    }

}
