package org.cardanofoundation.lob.app.config;


import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl.BlockfrostPublisher;
import org.cardanofoundation.lob.app.blockchain_common.service.ipfs.impl.IpfsNodePublisher;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.JsonSchemaMetadataChecker;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;

@Configuration
public class BlockchainCommonConfig {

    @Value("${lob.l1.transaction.metadata.validation.enable:true}")
    private boolean enableChecker;

    /**
     * Deliberately ungated: {@code blockchain_publisher}, {@code keri_attestation} and
     * {@code document_vault} each need this independently of the others' enablement flags. Stateless,
     * so there is nothing to gate.
     */
    @Bean
    public Cip170MetadataFactory cip170MetadataFactory() {
        return new Cip170MetadataFactory();
    }

    /** Ungated for the same reason as {@link #cip170MetadataFactory()}. */
    @Bean
    public DocumentIpfsSerialiser documentIpfsSerialiser(ObjectMapper objectMapper) {
        return new DocumentIpfsSerialiser(objectMapper);
    }

    /** Ungated for the same reason as {@link #cip170MetadataFactory()}. */
    @Bean
    public DocumentMetadataSerialiser documentMetadataSerialiser() {
        return new DocumentMetadataSerialiser();
    }

    @Bean
    //@Qualifier("api1JsonSchemaMetadataChecker")
    public MetadataChecker api1JsonSchemaMetadataChecker(ObjectMapper objectMapper,
                                                         @Value("classpath:api1_lob_blockchain_transaction_metadata_schema.json") Resource metadataSchemaResource
                                                         ) {
        val checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(metadataSchemaResource);
        checker.setEnableChecker(enableChecker);

        return checker;
    }

    @Bean
    //@Qualifier("api3JsonSchemaMetadataChecker")
    public MetadataChecker api3JsonSchemaMetadataChecker(ObjectMapper objectMapper,
                                                         @Value("classpath:api3_lob_blockchain_transaction_metadata_schema.json") Resource metadataSchemaResource
                                                         ) {
        val checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(metadataSchemaResource);
        checker.setEnableChecker(enableChecker);

        return checker;
    }

    @Bean
    //@Qualifier("spendingEventJsonSchemaMetadataChecker")
    public MetadataChecker spendingEventJsonSchemaMetadataChecker(ObjectMapper objectMapper,
                                                                  @Value("classpath:spending_event_blockchain_transaction_metadata-schema.json") Resource metadataSchemaResource
                                                                  ) {
        val checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(metadataSchemaResource);
        checker.setEnableChecker(enableChecker);

        return checker;
    }

    @Bean
    //@Qualifier("documentJsonSchemaMetadataChecker")
    public MetadataChecker documentJsonSchemaMetadataChecker(ObjectMapper objectMapper,
                                                              @Value("classpath:document_lob_blockchain_transaction_metadata_schema.json") Resource metadataSchemaResource
                                                              ) {
        val checker = new JsonSchemaMetadataChecker(objectMapper);
        checker.setMetadataSchemaResource(metadataSchemaResource);
        checker.setEnableChecker(enableChecker);

        return checker;
    }

    /**
     * The IPFS beans are declared HERE, not as {@code @Service}s in their own package, for the same
     * reason the serialisers are: this configuration class is always scanned, whereas a module's own
     * package is scanned only when that module is enabled. Registering them from
     * {@code BlockchainPublisherModuleConfig} would make them exist only on the publisher — which is
     * exactly the shape that left the api tier unable to name a CID during an attestation ceremony.
     *
     * <p>Gated purely on the IPFS properties, so a tier gets a publisher iff it is configured with one.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lob.blockchain_publisher.ipfs.blockfrost", value = "enabled", havingValue = "true", matchIfMissing = false)
    public IpfsPublisher blockfrostIpfsPublisher(
            @Value("${lob.blockchain_publisher.ipfs.blockfrost.url}") String url,
            @Value("${lob.blockchain_publisher.ipfs.blockfrost.project_id}") String projectId) {
        return new BlockfrostPublisher(url, projectId);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lob.blockchain_publisher.ipfs.local", value = "enabled", havingValue = "true", matchIfMissing = false)
    public IpfsPublisher localIpfsNodePublisher(
            @Value("${lob.blockchain_publisher.ipfs.local.node}") String node) {
        return new IpfsNodePublisher(node);
    }
}
