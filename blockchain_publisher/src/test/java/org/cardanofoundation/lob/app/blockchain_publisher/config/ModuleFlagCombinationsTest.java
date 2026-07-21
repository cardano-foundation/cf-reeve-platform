package org.cardanofoundation.lob.app.blockchain_publisher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.blockchain_common.service_assistance.MetadataChecker;
import org.cardanofoundation.lob.app.blockchain_publisher.job.DocumentAttestationFreezeCleanupJob;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationFreezeGuard;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationLookup;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.DocumentAttestationTargetProvider;
import org.cardanofoundation.lob.app.blockchain_publisher.service.keri.OrganiserWalletMetadataTxSubmitter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentConverter;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentIpfsSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentL1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.document.DocumentMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.report.API3MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.spendingevent.SpendingEventMetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.publish.module.transaction.API1MetadataSerialiser;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.BlockchainTransactionSubmissionService;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.service.AttestationFreezeGuard;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.document_vault.service.VaultKeyLookupService;
import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;
import org.cardanofoundation.lob.app.keri_attestation.service.Cip170MetadataFactory;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Task 16: pins the bean-level degradation matrix design §3.4 promises for
 * {@code lob.keri-attestation.enabled} x {@code lob.document_vault.enabled}, from
 * {@code blockchain_publisher}'s own composition root, {@link TransactionSubmissionConfig}.
 *
 * <p><b>Why not the real {@code KeriAttestationModuleConfig}/{@code DocumentVaultModuleConfig}:</b>
 * both are bare {@code @ConditionalOnProperty} + {@code @ComponentScan} markers (verified by reading
 * them directly) with no {@code @EnableJpaRepositories} of their own - the real app relies on a
 * top-level composition root for that. {@link org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationModuleFlagTest}
 * in {@code keri_attestation} only gets away with a bare {@code ApplicationContextRunner} because
 * that module's OWN test classpath happens to also expose a nested {@code @Configuration} test class
 * ({@code CeremonyRepositoryTest.TestConfig}, itself under the scanned package) that incidentally
 * supplies {@code @EnableJpaRepositories} + a real Postgres testcontainer - a side effect, not a
 * property of the module config itself. {@code blockchain_publisher}'s test classpath only carries
 * {@code document_vault}/{@code keri_attestation}'s <em>main</em> jars (see their
 * {@code implementation project(...)} declarations), not their test sources, so that same scan would
 * instead hit real {@code @Service} beans backed by JPA repository interfaces with no repository
 * infrastructure at all and fail context refresh for reasons unrelated to the flags under test. So
 * this test scopes to the real {@link TransactionSubmissionConfig} (the actual owner of every
 * "seam" bean below) plus hand-mocked collaborators, and stands in for the other two modules' own
 * conditional exposure of {@link AttestationConsumptionApi} / {@link VaultDocumentService} with
 * minimal stub configs carrying the *same* {@code @ConditionalOnProperty} gate as the real
 * {@code KeriAttestationModuleConfig} / {@code DocumentVaultModuleConfig} - so the conditional
 * evaluation itself is still real Spring, only the heavy internals are swapped for mocks. This
 * matches design §9's "Spring context tests for module enabled/disabled combinations" while keeping
 * assertions on the actual conditional wiring rather than "context didn't crash".
 *
 * <p><b>Why the nested stub classes below carry no {@code @Configuration}/{@code @Component}
 * stereotype at all:</b> found the hard way - several other {@code blockchain_publisher} tests (e.g.
 * {@code BlockchainPublisherServiceDuplicateEventsTest}) declare their own plain
 * {@code @ComponentScan(basePackages = "org.cardanofoundation.lob.app.blockchain_publisher", ...)}
 * directly on the test class (not via {@code @SpringBootApplication}), which reaches this class's
 * package too, since test and main classes share one runtime classpath. A {@code @Configuration}
 * (or even {@code @TestConfiguration} - its exclusion from scanning is opt-in per {@code
 * @ComponentScan} via Spring Boot's {@code TypeExcludeFilter}, and a raw {@code @ComponentScan} like
 * that one never opts in) nested class here would get swept into THEIR contexts too; worst case, a
 * same-named mock {@code @Bean} (e.g. {@code organisationPublicApi()}) silently overrides a real
 * production bean under {@code spring.main.allow-bean-definition-overriding=true}, breaking
 * unrelated tests in ways that only show up as a missing bean several layers away (confirmed by
 * reproducing it). A class with {@code @Bean} methods but no stereotype annotation is invisible to
 * ClassPathBeanDefinitionScanner (which filters by stereotype) yet still fully processed by
 * {@code ApplicationContextRunner.withUserConfiguration(...)} - Spring's well-documented "@Bean lite
 * mode" for explicitly-registered classes - so it works here without leaking anywhere else.
 */
class ModuleFlagCombinationsTest {

    private static final String KERI_ENABLED = "lob.keri-attestation.enabled";
    private static final String VAULT_ENABLED = "lob.document_vault.enabled";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    CollaboratorsConfig.class,
                    KeriAttestationSeamStubConfig.class,
                    DocumentVaultSeamStubConfig.class,
                    TransactionSubmissionConfig.class);

    // --- both flags off (module defaults) ---

    @Test
    void bothFlagsUnset_defaultToOff_noAttestationSeamsNoVaultService() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertNoKeriSeamBeans(context);
            assertThat(context).doesNotHaveBean(VaultDocumentService.class);
            assertDocumentCreatorWiredWithoutAttestationLookup(context);
        });
    }

    @Test
    void bothFlagsExplicitlyFalse_noAttestationSeamsNoVaultService() {
        contextRunner.withPropertyValues(KERI_ENABLED + "=false", VAULT_ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertNoKeriSeamBeans(context);
            assertThat(context).doesNotHaveBean(VaultDocumentService.class);
            assertDocumentCreatorWiredWithoutAttestationLookup(context);
        });
    }

    // --- keri off, document_vault on: design §3.4's document_vault row - "no hard bean dependency,
    // context starts cleanly" - proven here by constructing the REAL VaultDocumentService (not a
    // mock) so its two ObjectProvider seams (AttestationFreezeGuard, AttestationConsumptionApi)
    // genuinely resolve to empty via Spring, not merely via a hand-waved stub. ---

    @Test
    void keriOff_vaultOn_vaultServiceConstructsWithEmptyAttestationSeams_noKeriBeans() {
        contextRunner.withPropertyValues(KERI_ENABLED + "=false", VAULT_ENABLED + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertNoKeriSeamBeans(context);
            assertThat(context).hasSingleBean(VaultDocumentService.class);
            assertDocumentCreatorWiredWithoutAttestationLookup(context);
        });
    }

    // --- keri on, document_vault off: fixed per design §3.4 (Task 16 follow-up). Originally this
    // combination failed context refresh outright, because documentAttestationTargetProvider took a
    // REQUIRED (non-Optional) VaultDocumentService and was gated only on keri's flag. Of the five
    // keri-gated beans in TransactionSubmissionConfig, ONLY documentAttestationTargetProvider
    // actually needs a document_vault bean (verified by reading each one's constructor deps
    // directly: documentAttestationLookup, cardanoMetadataTxSubmitter, documentAttestationFreezeGuard
    // and documentAttestationFreezeCleanupJob all depend only on blockchain_publisher/keri_attestation
    // types, never VaultDocumentService) - so it alone now also requires
    // lob.document_vault.enabled=true. With keri ON + vault OFF: the attestation module still runs
    // (dispatch-side lookup, wallet tx submission, freeze guard/cleanup all wired); only
    // document-target ceremony CREATION is unavailable, rejected at runtime by keri_attestation's
    // provider-registry lookup (TARGET_MISMATCH, since no DOCUMENT-targeted provider is registered) -
    // not a context failure. ---

    @Test
    void keriOn_vaultOff_contextLoadsCleanly_noVaultDependentBeansOtherKeriSeamsPresent() {
        contextRunner.withPropertyValues(KERI_ENABLED + "=true", VAULT_ENABLED + "=false").run(context -> {
            assertThat(context).hasNotFailed();

            // keri-only seams: present, unaffected by document_vault being off.
            assertThat(context).hasSingleBean(DocumentAttestationLookup.class);
            assertThat(context).hasSingleBean(OrganiserWalletMetadataTxSubmitter.class);
            assertThat(context).hasSingleBean(DocumentAttestationFreezeGuard.class);
            assertThat(context).hasSingleBean(DocumentAttestationFreezeCleanupJob.class);
            assertThat(context).hasSingleBean(AttestationConsumptionApi.class);

            // vault-dependent seam and document_vault itself: absent, no failure.
            assertThat(context).doesNotHaveBean(DocumentAttestationTargetProvider.class);
            assertThat(context).doesNotHaveBean(VaultDocumentService.class);

            // dispatch-side lookup (consumption, not ceremony creation) only needs keri, so it's
            // still wired into the document creator even with document_vault off.
            DocumentL1TransactionCreator creator = context.getBean(DocumentL1TransactionCreator.class);
            @SuppressWarnings("unchecked")
            Optional<DocumentAttestationLookup> lookup = (Optional<DocumentAttestationLookup>)
                    ReflectionTestUtils.getField(creator, "attestationLookup");
            assertThat(lookup).isPresent();
        });
    }

    // --- both on: every seam bean present, DocumentL1TransactionCreator's Optional lookup populated ---

    @Test
    void bothFlagsOn_allAttestationSeamsPresent_documentCreatorLookupPopulated() {
        contextRunner.withPropertyValues(KERI_ENABLED + "=true", VAULT_ENABLED + "=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DocumentAttestationLookup.class);
            assertThat(context).hasSingleBean(OrganiserWalletMetadataTxSubmitter.class);
            assertThat(context).hasSingleBean(DocumentAttestationTargetProvider.class);
            assertThat(context).hasSingleBean(DocumentAttestationFreezeGuard.class);
            assertThat(context).hasSingleBean(DocumentAttestationFreezeCleanupJob.class);
            assertThat(context).hasSingleBean(AttestationConsumptionApi.class);
            assertThat(context).hasSingleBean(VaultDocumentService.class);

            DocumentL1TransactionCreator creator = context.getBean(DocumentL1TransactionCreator.class);
            @SuppressWarnings("unchecked")
            Optional<DocumentAttestationLookup> lookup =
                    (Optional<DocumentAttestationLookup>) ReflectionTestUtils.getField(creator, "attestationLookup");
            assertThat(lookup).isPresent();
        });
    }

    // --- four of the five keri-gated seam beans in TransactionSubmissionConfig are gated ONLY on
    // lob.keri-attestation.enabled, never on lob.keri-attestation.keria.url (that narrower gate lives
    // on keri_attestation's own SignifyClientConfig, a different config class entirely). The fifth,
    // documentAttestationTargetProvider, additionally requires lob.document_vault.enabled (Task 16
    // follow-up fix, see the keriOn_vaultOff test above) - still never keria.url. document_vault is
    // left ON here so documentAttestationTargetProvider can be asserted present too; it is not what
    // this test is about. ---

    @Test
    void keriSeamBeans_unaffectedByKeriaUrl_presentWhetherUrlSetOrUnset() {
        contextRunner
                .withPropertyValues(KERI_ENABLED + "=true", VAULT_ENABLED + "=true")
                .run(context -> assertAllKeriSeamBeansPresent(context));

        contextRunner
                .withPropertyValues(
                        KERI_ENABLED + "=true",
                        VAULT_ENABLED + "=true",
                        "lob.keri-attestation.keria.url=https://keria.example.org")
                .run(context -> assertAllKeriSeamBeansPresent(context));
    }

    private static void assertAllKeriSeamBeansPresent(
            AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(DocumentAttestationLookup.class);
        assertThat(context).hasSingleBean(OrganiserWalletMetadataTxSubmitter.class);
        assertThat(context).hasSingleBean(DocumentAttestationTargetProvider.class);
        assertThat(context).hasSingleBean(DocumentAttestationFreezeGuard.class);
        assertThat(context).hasSingleBean(DocumentAttestationFreezeCleanupJob.class);
    }

    // Callers of this helper always have BOTH flags off (or vault off, keri off) - never the
    // keri-ON+vault-OFF combination, which now leaves documentAttestationTargetProvider absent for a
    // different reason (missing document_vault, not missing keri) - see
    // keriOn_vaultOff_contextLoadsCleanly_noVaultDependentBeansOtherKeriSeamsPresent for that case.
    private static void assertNoKeriSeamBeans(
            AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(DocumentAttestationLookup.class);
        assertThat(context).doesNotHaveBean(OrganiserWalletMetadataTxSubmitter.class);
        assertThat(context).doesNotHaveBean(DocumentAttestationTargetProvider.class);
        assertThat(context).doesNotHaveBean(DocumentAttestationFreezeGuard.class);
        assertThat(context).doesNotHaveBean(DocumentAttestationFreezeCleanupJob.class);
        assertThat(context).doesNotHaveBean(AttestationConsumptionApi.class);
    }

    private static void assertDocumentCreatorWiredWithoutAttestationLookup(
            AssertableApplicationContext context) {
        assertThat(context).hasSingleBean(DocumentL1TransactionCreator.class);
        DocumentL1TransactionCreator creator = context.getBean(DocumentL1TransactionCreator.class);
        Optional<?> lookup = (Optional<?>) ReflectionTestUtils.getField(creator, "attestationLookup");
        assertThat(lookup).isEmpty();
    }

    /**
     * Stands in for {@code KeriAttestationModuleConfig}'s conditional exposure of
     * {@link AttestationConsumptionApi} (design: the port {@code document_vault} and
     * {@code blockchain_publisher} both consume). Same property/value as the real module config.
     */
    @ConditionalOnProperty(name = KERI_ENABLED, havingValue = "true", matchIfMissing = false)
    static class KeriAttestationSeamStubConfig {

        @Bean
        AttestationConsumptionApi attestationConsumptionApi() {
            return mock(AttestationConsumptionApi.class);
        }
    }

    /**
     * Stands in for {@code DocumentVaultModuleConfig}'s conditional component-scan exposing a real
     * {@link VaultDocumentService}. Unlike the keri stub above, this constructs the REAL service
     * class (with mocked collaborators) rather than a mock of it, because one of this test's
     * assertions is specifically that {@code VaultDocumentService}'s own two {@link ObjectProvider}
     * seams resolve without error when nothing satisfies them - a claim only true construction can
     * verify.
     */
    @ConditionalOnProperty(name = VAULT_ENABLED, havingValue = "true", matchIfMissing = false)
    static class DocumentVaultSeamStubConfig {

        @Bean
        VaultDocumentService vaultDocumentService(
                VaultDocumentRepository documentRepository,
                VaultKeyLookupService keyLookupService,
                KeycloakSecurityHelper securityHelper,
                OrganisationPublicApiIF organisationPublicApi,
                ApplicationEventPublisher eventPublisher,
                ObjectProvider<IpfsAvailability> ipfsAvailability,
                ObjectProvider<AttestationFreezeGuard> attestationFreezeGuardProvider,
                ObjectProvider<AttestationConsumptionApi> attestationConsumptionApiProvider) {
            return new VaultDocumentService(
                    documentRepository,
                    keyLookupService,
                    securityHelper,
                    organisationPublicApi,
                    eventPublisher,
                    ipfsAvailability,
                    attestationFreezeGuardProvider,
                    attestationConsumptionApiProvider);
        }
    }

    /**
     * Every non-attestation collaborator {@link TransactionSubmissionConfig}'s unconditional
     * {@code @Bean} methods need (api1/spendingEvent/api3/document creators, transaction submission
     * service) plus the attestation-gated methods' own local dependencies - present regardless of
     * flag state so only the flag-gated beans vary across test methods.
     */
    static class CollaboratorsConfig {

        @Bean
        @Qualifier("yaci_blockfrost")
        BackendService yaciBlockfrostBackendService() {
            return mock(BackendService.class);
        }

        @Bean
        BlockchainTransactionSubmissionService blockchainTransactionSubmissionService() {
            return mock(BlockchainTransactionSubmissionService.class);
        }

        @Bean
        UtxoSupplier utxoSupplier() {
            return mock(UtxoSupplier.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        API1MetadataSerialiser api1MetadataSerialiser() {
            return mock(API1MetadataSerialiser.class);
        }

        @Bean
        SpendingEventMetadataSerialiser spendingEventMetadataSerialiser() {
            return mock(SpendingEventMetadataSerialiser.class);
        }

        @Bean
        API3MetadataSerialiser api3MetadataSerialiser() {
            return mock(API3MetadataSerialiser.class);
        }

        @Bean
        BlockchainReaderPublicApiIF blockchainReaderPublicApi() {
            return mock(BlockchainReaderPublicApiIF.class);
        }

        // Qualified by bean name against the consumers' @Qualifier(...) values, same convention
        // BlockchainCommonConfig's real producers use (their @Qualifier annotations are commented
        // out there too - name-based fallback matching is the established idiom in this codebase).
        @Bean
        MetadataChecker api1JsonSchemaMetadataChecker() {
            return mock(MetadataChecker.class);
        }

        @Bean
        MetadataChecker spendingEventJsonSchemaMetadataChecker() {
            return mock(MetadataChecker.class);
        }

        @Bean
        MetadataChecker api3JsonSchemaMetadataChecker() {
            return mock(MetadataChecker.class);
        }

        @Bean
        MetadataChecker documentJsonSchemaMetadataChecker() {
            return mock(MetadataChecker.class);
        }

        @Bean
        Account organiserAccount() {
            return new Account();
        }

        @Bean
        DocumentIpfsSerialiser documentIpfsSerialiser() {
            return mock(DocumentIpfsSerialiser.class);
        }

        @Bean
        DocumentMetadataSerialiser documentMetadataSerialiser() {
            return mock(DocumentMetadataSerialiser.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        DocumentAttestationFreezeRepository documentAttestationFreezeRepository() {
            return mock(DocumentAttestationFreezeRepository.class);
        }

        @Bean
        Cip170MetadataFactory cip170MetadataFactory() {
            return new Cip170MetadataFactory();
        }

        @Bean
        DocumentConverter documentConverter() {
            return mock(DocumentConverter.class);
        }

        @Bean
        KeycloakSecurityHelper keycloakSecurityHelper() {
            return mock(KeycloakSecurityHelper.class);
        }

        @Bean
        KeriAttestationProperties keriAttestationProperties() {
            return new KeriAttestationProperties(
                    true,
                    new KeriAttestationProperties.Keria("https://keria.example.org", null, "bran"),
                    "reeve",
                    new KeriAttestationProperties.CredentialPolicy(List.of(), List.of()),
                    Duration.ofHours(1),
                    Duration.ofHours(24),
                    Duration.ofMinutes(3),
                    Duration.ofSeconds(2),
                    3,
                    null,
                    Duration.ofSeconds(15),
                    Duration.ofMinutes(30),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(3),
                    Duration.ofMinutes(2),
                    null);
        }

        @Bean
        VaultDocumentRepository vaultDocumentRepository() {
            return mock(VaultDocumentRepository.class);
        }

        @Bean
        VaultKeyLookupService vaultKeyLookupService() {
            return mock(VaultKeyLookupService.class);
        }

        @Bean
        OrganisationPublicApiIF organisationPublicApi() {
            return mock(OrganisationPublicApiIF.class);
        }
    }
}
