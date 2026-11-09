package org.cardanofoundation.lob.app.document_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.service.AttestationFreezeGuard;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationTargetProvider;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationTargetProviderRegistry;

/**
 * The executable definition of the `api` container in cf-reeve-application's docker-compose.yml:
 *
 * <pre>
 *   LOB_DOCUMENT_VAULT_ENABLED:       true
 *   LOB_KERI_ATTESTATION_ENABLED:     true
 *   LOB_BLOCKCHAIN_PUBLISHER_ENABLED: false   &lt;- and so ABSENT from this test's classpath entirely
 *   LOB_BLOCKCHAIN_READER_ENABLED:    false
 *   (no IPFS configuration of any kind)
 * </pre>
 *
 * <p>That deployment previously returned {@code 422 TARGET_MISMATCH — "No provider for target type
 * DOCUMENT."} on {@code POST /api/v1/keri-attestation/ceremonies}, because the sole
 * {@link AttestationTargetProvider} was registered from a {@code blockchain_publisher} configuration
 * class whose package is never component-scanned when that module is off. Relocating the provider is
 * necessary but not sufficient: it also has to stop needing an {@code IpfsPublisher} and a chain tip,
 * because neither exists on this pod — which is why the attested digest covers a content commitment
 * the vault can compute offline rather than the finished 1447 manifest.
 *
 * <p>{@link DocumentVaultWithKeriNoPublisherContextTest} is this test's predecessor and asserted the
 * OPPOSITE about {@link AttestationFreezeGuard} — that no implementation existed on this classpath.
 * That was an accurate description of the old architecture and the reason attestation was unavailable
 * on the api pod; it has been updated alongside this class.
 *
 * <p>Reuses {@link DocumentVaultContextIntegrationTest.TestConfig} rather than declaring another
 * broad-scanning nested config, for the reason that test's javadoc documents at length (its
 * {@code @ComponentScan} carries no {@code TestTypeExcludeFilter}, so a second nested configuration
 * anywhere in this module's test tree gets swept into every other context test). The distinct property
 * set below is what earns this class its own cached context.
 */
@SpringBootTest(properties = "lob.keri-attestation.enabled=true")
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class ApiPodDocumentAttestationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AttestationTargetProviderRegistry registry;

    /**
     * The regression this whole exercise exists to prevent. An empty registry is a legitimate state as
     * far as {@code AttestationTargetProviderRegistry} is concerned — it simply indexes whatever beans
     * Spring hands it — so nothing failed at startup and the gap only surfaced as a 422 at runtime, in
     * a deployed environment. This asserts the provider is actually there.
     */
    @Test
    void aDocumentAttestationProviderIsRegisteredWithoutBlockchainPublisher() {
        assertThat(registry.forType("DOCUMENT"))
                .as("api pod must resolve a DOCUMENT AttestationTargetProvider; its absence is the "
                        + "TARGET_MISMATCH 422 on POST /ceremonies")
                .isPresent();
    }

    /**
     * The freeze guard is consulted by {@code VaultDocumentService#publish} before consuming a
     * ceremony. On the api pod it must be a real implementation, not an empty ObjectProvider — an
     * absent guard means every attested publish fails closed, i.e. the feature is unavailable exactly
     * where it is supposed to run.
     */
    @Test
    void theFreezeGuardIsAvailableOnTheApiPod() {
        assertThat(context.getBeanNamesForType(AttestationFreezeGuard.class)).hasSize(1);
    }

    /**
     * Guards the constraint that actually forces the design: this pod has no {@code IpfsPublisher} bean
     * and no {@code BlockchainReaderPublicApiIF} bean, so anything on the freeze path that reaches for
     * either would fail here rather than in production. Asserted by class NAME so that this test does
     * not itself introduce a compile-time dependency on blockchain_publisher / blockchain_reader.
     */
    @Test
    void neitherIpfsNorChainTipIsAvailableOnThisPod() {
        assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("ipfspublisher"));
        assertThat(context.getBeanNamesForType(Object.class))
                .filteredOn(name -> name.toLowerCase().contains("blockchainreaderpublicapi"))
                .isEmpty();
    }
}
