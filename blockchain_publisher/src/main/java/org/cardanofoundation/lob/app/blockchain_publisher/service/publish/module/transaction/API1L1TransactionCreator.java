package org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction;

import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.AbstractL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.L1TransactionCreatorConfig;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@Slf4j
public class API1L1TransactionCreator extends AbstractL1TransactionCreator<TransactionEntity> {

    private final API1MetadataSerialiser api1MetadataSerialiser;

    public API1L1TransactionCreator(BackendService backendService,
                                    API1MetadataSerialiser api1MetadataSerialiser,
                                    BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                    MetadataChecker jsonSchemaMetadataChecker,
                                    Account organiserAccount,
                                    Optional<IpfsPublisher> ipfsPublisher,
                                    L1TransactionCreatorConfig config) {
        super(backendService, blockchainReaderPublicApi, jsonSchemaMetadataChecker, organiserAccount, ipfsPublisher, config);
        this.api1MetadataSerialiser = api1MetadataSerialiser;
    }

    @Override
    protected MetadataMap serialiseToMetadataMap(String organisationId, Set<TransactionEntity> batch, long creationSlot) {
        return api1MetadataSerialiser.serialiseToMetadataMap(organisationId, batch, creationSlot);
    }

    @Override
    protected String metadataFilePrefix() {
        return "lob-txs-api1-metadata";
    }

}
