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
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.job.DocumentAttestationFreezeCleanupJob;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.KeriService;
import org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs.IpfsPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationFreezeGuard;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationLookup;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationTargetProvider;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.OrganiserWalletMetadataTxSubmitter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.L1TransactionCreatorConfig;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.*;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;
import org.cardanofoundation.lob.app.keri_attestation.service.CardanoMetadataTxSubmitter;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Configuration
public class TransactionSubmissionConfig {

    /** CIP-170 attestation metadata itself is always published under label 170 ({@code
     *  Cip170MetadataFactory}) - reserved and never available for a document/API1/API3/spending-event
     *  metadata label (R3 fix, Codex re-verification). Mirrors {@code
     *  DocumentAttestationLookup#RESERVED_CIP_170_LABEL}; duplicated as a literal here (not shared)
     *  since this class must reject it even when {@code DocumentAttestationLookup} is never created
     *  (KERI disabled) - see {@link #documentL1TransactionCreator}'s guard for why. */
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
                                                                      DocumentIpfsSerialiser documentIpfsSerialiser,
                                                                      DocumentMetadataSerialiser documentMetadataSerialiser,
                                                                      BlockchainReaderPublicApiIF blockchainReaderPublicApi,
                                                                      @Qualifier("documentJsonSchemaMetadataChecker") MetadataChecker metadataChecker,
                                                                      Account organiserAccount,
                                                                      Optional<IpfsPublisher> ipfsPublisher,
                                                                      Optional<DocumentAttestationLookup> attestationLookup,
                                                                      @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel,
                                                                      @Value("${lob.l1.transaction.debug_store_output_tx:false}") boolean debugStoreOutputTx
    ) {
        // R3 fix (Codex re-verification): this bean carries NO @ConditionalOnProperty, unlike
        // documentAttestationLookup below (only created when lob.keri-attestation.enabled=true, whose
        // OWN constructor carries the equivalent guard). With KERI disabled, documentAttestationLookup
        // is never created and its guard never runs at all - a deployment configuring the document
        // metadata label to the CIP-170-reserved 170 would then have nothing rejecting it, and a plain
        // (unattested) document publish could collide with (or overwrite) label 170's CIP-170
        // attestation map. Checked here too, unconditionally, so every deployment shape - KERI on or
        // off - fails fast at context startup instead of silently corrupting on-chain metadata the
        // first time a document is dispatched.
        if (metadataLabel == RESERVED_CIP_170_LABEL) {
            throw new IllegalStateException("metadata label 170 is reserved for CIP-170 attestations");
        }
        return new DocumentL1TransactionCreator(backendService,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                metadataChecker,
                organiserAccount,
                ipfsPublisher,
                attestationLookup,
                metadataLabel,
                debugStoreOutputTx
        );
    }

    @Bean
    @ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
    public DocumentAttestationLookup documentAttestationLookup(
            DocumentAttestationFreezeRepository documentAttestationFreezeRepository,
            AttestationConsumptionApi attestationConsumptionApi,
            Cip170MetadataFactory cip170MetadataFactory,
            // Same property (and default) documentL1TransactionCreator/documentAttestationTargetProvider
            // are wired with above - so loadForDispatch's label check (M3 cross-review F3 fix) compares
            // against the label dispatch is ACTUALLY publishing under, not a value that could silently
            // drift from it. DocumentAttestationLookup's own constructor fails fast if this is ever 170
            // (reserved for the CIP-170 attestation map itself).
            @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel
    ) {
        return new DocumentAttestationLookup(documentAttestationFreezeRepository, attestationConsumptionApi,
                cip170MetadataFactory, metadataLabel);
    }

    @Bean
    @ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
    public CardanoMetadataTxSubmitter cardanoMetadataTxSubmitter(@Qualifier("yaci_blockfrost") BackendService backendService,
                                                                  Account organiserAccount,
                                                                  ObjectMapper objectMapper
    ) {
        return new OrganiserWalletMetadataTxSubmitter(backendService, organiserAccount, objectMapper);
    }

    @Bean
    // Unlike its four sibling attestation beans below, this one takes a REQUIRED (non-Optional)
    // VaultDocumentService - document-target ceremony creation genuinely cannot work without
    // document_vault, so it needs both flags, not just keri's. With keri ON + document_vault OFF the
    // attestation module itself still runs (future non-document targets); ceremony creation for
    // DOCUMENT specifically then fails at the provider-registry lookup (TARGET_MISMATCH) rather than
    // this bean failing context refresh entirely (Task 16 finding).
    @ConditionalOnProperty(
            name = {"lob.keri-attestation.enabled", "lob.document_vault.enabled"},
            havingValue = "true",
            matchIfMissing = false)
    public DocumentAttestationTargetProvider documentAttestationTargetProvider(
            VaultDocumentService vaultDocumentService,
            DocumentConverter documentConverter,
            DocumentIpfsSerialiser documentIpfsSerialiser,
            DocumentMetadataSerialiser documentMetadataSerialiser,
            BlockchainReaderPublicApiIF blockchainReaderPublicApi,
            Optional<IpfsPublisher> ipfsPublisher,
            Cip170MetadataFactory cip170MetadataFactory,
            DocumentAttestationFreezeRepository documentAttestationFreezeRepository,
            KeycloakSecurityHelper securityHelper,
            Clock clock,
            // Same property (and default) documentL1TransactionCreator's metadataTag is wired with
            // above - so a freeze's ceremony.metadataLabel / ConsumedAttestation.metadataLabel can
            // never disagree with the label dispatch actually publishes under (M3 finding).
            @Value("${lob.l1.transaction.metadata_label:1447}") int metadataLabel
    ) {
        return new DocumentAttestationTargetProvider(
                vaultDocumentService,
                documentConverter,
                documentIpfsSerialiser,
                documentMetadataSerialiser,
                blockchainReaderPublicApi,
                ipfsPublisher,
                cip170MetadataFactory,
                documentAttestationFreezeRepository,
                securityHelper,
                clock,
                metadataLabel
        );
    }

    @Bean
    @ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
    public DocumentAttestationFreezeGuard documentAttestationFreezeGuard(
            DocumentAttestationFreezeRepository documentAttestationFreezeRepository,
            DocumentConverter documentConverter,
            DocumentIpfsSerialiser documentIpfsSerialiser,
            KeriAttestationProperties keriAttestationProperties,
            Clock clock
    ) {
        return new DocumentAttestationFreezeGuard(
                documentAttestationFreezeRepository,
                documentConverter,
                documentIpfsSerialiser,
                keriAttestationProperties,
                clock
        );
    }

    @Bean
    @ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
    public DocumentAttestationFreezeCleanupJob documentAttestationFreezeCleanupJob(
            DocumentAttestationFreezeRepository documentAttestationFreezeRepository,
            AttestationConsumptionApi attestationConsumptionApi,
            Clock clock
    ) {
        return new DocumentAttestationFreezeCleanupJob(documentAttestationFreezeRepository, attestationConsumptionApi, clock);
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
