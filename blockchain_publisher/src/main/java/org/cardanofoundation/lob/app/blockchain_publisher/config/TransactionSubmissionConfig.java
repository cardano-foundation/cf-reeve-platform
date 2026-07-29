package org.cardanofoundation.lob.app.blockchain_publisher.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.service.KeriService;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.OrganiserWalletMetadataReader;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.L1TransactionCreatorConfig;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.authbegin.AuthBeginL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.*;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.keri_attestation.service.CardanoMetadataReader;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@Configuration
public class TransactionSubmissionConfig {

    /**
     * Label 170 carries CIP-170 attestation metadata, so no publishable type may be configured to use
     * it. Enforced by {@link #documentL1TransactionCreator}.
     */
    private static final int RESERVED_CIP_170_LABEL = 170;

    @Bean
    @Profile(value = { "blockfrost", "dev--yaci-dev-kit", "test"} )
    public BlockchainTransactionSubmissionService backendServiceTransactionSubmissionService(
            @Qualifier("yaci_blockfrost") BackendService backendService) {
        return new BackendServiceBlockchainTransactionSubmissionService(backendService);
    }

    @Bean
    @Profile(value = { "blockfrost", "dev--yaci-dev-kit", "test"} )
    public UtxoSupplier utxoSupplier(@Qualifier("yaci_blockfrost") BackendService backendService) {
        return new DefaultUtxoSupplier(backendService.getUtxoService());
    }

    @Bean
    public TransactionSubmissionService transactionSubmissionService(
            BlockchainTransactionSubmissionService trxSubmissionService,
            @Qualifier("yaci_blockfrost") BackendService backendService,
            UtxoSupplier utxoSupplier,
            Clock clock,
            @Value("${lob.transaction.submission.sleep.seconds:5}") int sleepTimeSeconds,
            @Value("${lob.transaction.submission.timeout.in.seconds:300}") int timeoutInSeconds
    ) {
        return new DefaultTransactionSubmissionService(trxSubmissionService,
                backendService,
                utxoSupplier,
                clock,
                sleepTimeSeconds,
                timeoutInSeconds
        );
    }

    @Bean
    public API1L1TransactionCreator api1L1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                             API1MetadataSerialiser metadataSerialiser,
                                                             BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                             @Qualifier("api1JsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                             Account organiserAccount,
                                                             Optional<IpfsPublisher> ipfsPublisher,
                                                             @Value("${lob.transaction.ipfs.enabled:false}") boolean useIpfs,
                                                             @Value("${l1.transaction.metadata_label:1447}") int metadataLabel,
                                                             @Value("${l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        return new API1L1TransactionCreator(backendService,
                metadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                ipfsPublisher,
                new L1TransactionCreatorConfig(useIpfs, metadataLabel, debugStoreOutputTx)
        );
    }

    @Bean
    public SpendingEventL1TransactionCreator spendingEventL1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                                              SpendingEventMetadataSerialiser metadataSerialiser,
                                                                              BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                                              @Qualifier("spendingEventJsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                                              Account organiserAccount,
                                                                              Optional<IpfsPublisher> ipfsPublisher,
                                                                              @Value("${lob.funding.ipfs.enabled:false}") boolean useIpfs,
                                                                              @Value("${l1.transaction.metadata_label:1447}") int metadataLabel,
                                                                              @Value("${lob.l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        return new SpendingEventL1TransactionCreator(backendService,
                metadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                ipfsPublisher,
                new L1TransactionCreatorConfig(useIpfs, metadataLabel, debugStoreOutputTx)
        );
    }

    @Bean
    public API3L1TransactionCreator api3L1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                             API3MetadataSerialiser metadataSerialiser,
                                                             BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                             @Qualifier("api3JsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                             Account organiserAccount,
                                                             @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel,
                                                             @Value("${lob.l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx,
                                                             @Value("${lob.blockchain_publisher.keri.enabled:false}") boolean keriEnabled,
                                                             Optional<KeriService> keriService,
                                                             @Value("${lob.blockchain_publisher.keri.metadata_label:1}") int keriMetadataLabel
    ) {
        return new API3L1TransactionCreator(backendService,
                metadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                metadataLabel,
                debugStoreOutputTx,
                keriEnabled,
                keriService,
                keriMetadataLabel
        );
    }

    @Bean
    public DocumentL1TransactionCreator documentL1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                                      DocumentConverter documentConverter,
                                                                      DocumentIpfsSerialiser documentIpfsSerialiser,
                                                                      DocumentMetadataSerialiser documentMetadataSerialiser,
                                                                      BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                                      @Qualifier("documentJsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                                      OrganisationPublicApi organisationPublicApi,
                                                                      Account organiserAccount,
                                                                      Optional<IpfsPublisher> ipfsPublisher,
                                                                      Cip170MetadataFactory cip170MetadataFactory,
                                                                      @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel,
                                                                      @Value("${lob.l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        // Unconditional, so a misconfigured label fails at startup in every deployment shape rather
        // than overwriting a CIP-170 attestation map the first time a document is dispatched.
        if (metadataLabel == RESERVED_CIP_170_LABEL) {
            throw new IllegalStateException("metadata label 170 is reserved for CIP-170 attestations");
        }
        return new DocumentL1TransactionCreator(backendService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organisationPublicApi,
                organiserAccount,
                ipfsPublisher,
                cip170MetadataFactory,
                metadataLabel,
                debugStoreOutputTx
        );
    }

    /**
     * Read-only chain access for {@code keri_attestation}'s card-import verifier. This module submits
     * AUTH_BEGIN through {@link #authBeginL1TransactionCreator} and the normal dispatcher, so nothing
     * here hands a submitter to another module.
     */
    @Bean
    @ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
    public CardanoMetadataReader cardanoMetadataReader(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                       ObjectMapper objectMapper
    ) {
        return new OrganiserWalletMetadataReader(backendService, objectMapper);
    }

    @Bean
    public AuthBeginL1TransactionCreator authBeginL1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                                       BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                                       Cip170MetadataFactory cip170MetadataFactory,
                                                                       Account organiserAccount
    ) {
        return new AuthBeginL1TransactionCreator(backendService, blockchainReaderPublicApi, cip170MetadataFactory,
                organiserAccount);
    }

//    @Bean
//    @Profile("dev--preprod")
//    public BlockchainTransactionSubmissionService noopCardanoSummitTransactionSubmissionService() {
//        return new BlockchainTransactionSubmissionService.Noop();
//    }

    @Bean
    @Profile( value = { "submit-api" } )
    public BlockchainTransactionSubmissionService cardanoSummitTransactionSubmissionService(HttpClient httpClient,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.url}") String cardanoSubmitApiUrl,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.timeout.in.seconds}") int timeoutInSeconds,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.api_key}") String apiKey) {
        return new CardanoSubmitApiBlockchainTransactionSubmissionService(cardanoSubmitApiUrl, apiKey, httpClient, timeoutInSeconds);
    }

    @Bean
    @Profile( value = { "submit-api" } )
    public HttpClient httpClient(@Value("${lob.blockchain_publisher.tx.submit.timeout.in.seconds:30}") int timeoutInSeconds) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(timeoutInSeconds))
                .build();
    }

}
