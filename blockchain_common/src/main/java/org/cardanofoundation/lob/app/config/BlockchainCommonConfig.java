package org.cardanofoundation.lob.app.config;

import java.time.Clock;

import lombok.val;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    public DocumentMetadataSerialiser documentMetadataSerialiser(Clock clock) {
        return new DocumentMetadataSerialiser(clock);
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

}
