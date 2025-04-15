package org.cardanofoundation.lob.app.blockchain_publisher.config;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;

import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API1MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.*;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@Configuration
public class TransactionSubmissionConfig {

    @Bean
    @Profile(value = { "dev--yaci-dev-kit", "test"} )
    public BlockchainTransactionSubmissionService backendServiceTransactionSubmissionService(
            @Qualifier("yaci_blockfrost") BackendService backendService) {
        return new BackendServiceBlockchainTransactionSubmissionService(backendService);
    }

    @Bean
    @Profile(value = { "dev--yaci-dev-kit", "test", "prod"} )
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
                                                             @Value("${l1.transaction.metadata_label:1447}") int metadataLabel,
                                                             @Value("${l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        return new API1L1TransactionCreator(backendService,
                metadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                metadataLabel,
                debugStoreOutputTx
        );
    }

    @Bean
    public API3L1TransactionCreator api3L1TransactionCreator(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                             API3MetadataSerialiser metadataSerialiser,
                                                             BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                             @Qualifier("api3JsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                             Account organiserAccount,
                                                             @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel,
                                                             @Value("${lob.l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        return new API3L1TransactionCreator(backendService,
                metadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                metadataLabel,
                debugStoreOutputTx
        );
    }

//    @Bean
//    @Profile("dev--preprod")
//    public BlockchainTransactionSubmissionService noopCardanoSummitTransactionSubmissionService() {
//        return new BlockchainTransactionSubmissionService.Noop();
//    }

    @Bean
    @Profile( value = { "prod", "dev--preprod" } )
    public BlockchainTransactionSubmissionService cardanoSummitTransactionSubmissionService(HttpClient httpClient,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.url}") String cardanoSubmitApiUrl,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.timeout.in.seconds}") int timeoutInSeconds,
                                                                                            @Value("${lob.blockchain_publisher.tx.submit.api_key}") String apiKey) {
        return new CardanoSubmitApiBlockchainTransactionSubmissionService(cardanoSubmitApiUrl, apiKey, httpClient, timeoutInSeconds);
    }

    @Bean
    @Profile( value = { "prod", "dev--preprod" } )
    public HttpClient httpClient(@Value("${lob.blockchain_publisher.tx.submit.timeout.in.seconds:30}") int timeoutInSeconds) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(timeoutInSeconds))
                .build();
    }

}
