package org.cardanofoundation.lob.app.document_vault.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.service.AttestationFreezeGuard;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;

/**
 * M3 milestone-review finding: no Spring-context test previously proved the three-way combination
 * {@code document_vault=on + keri-attestation=on + blockchain_publisher ABSENT} starts cleanly — the
 * combo where {@link AttestationFreezeGuard} (implemented ONLY by {@code blockchain_publisher}) has
 * no implementation anywhere on the classpath, so {@code VaultDocumentService}'s
 * {@code ObjectProvider<AttestationFreezeGuard>} must resolve empty rather than fail context refresh
 * (see {@code VaultDocumentService#consumeAttestation}'s fail-closed handling of that case).
 *
 * <p>Deliberately REUSES {@link DocumentVaultContextIntegrationTest.TestConfig} rather than declaring
 * a second, near-identical, broad-scanning nested {@code TestConfig} of its own: as
 * {@code DocumentLedgerUpdateHandlerRollbackIntegrationTest}'s javadoc documents (found the hard way),
 * that shared {@code TestConfig}'s {@code @ComponentScan(basePackages = "org.cardanofoundation.lob")}
 * carries no {@code TestTypeExcludeFilter} (only wired in automatically for
 * {@code @SpringBootApplication}-style scans, never for a raw {@code @ComponentScan} like this one),
 * so any second nested {@code @Configuration}/{@code @SpringBootConfiguration} test class anywhere in
 * document_vault's test tree — including one declaring its own {@code Clock} bean — gets swept into
 * every other document_vault context test's scan too, and vice versa, producing a
 * {@code BeanDefinitionOverrideException} for the duplicate bean name. {@code
 * DocumentDispatchRetryJobStarvationIntegrationTest} and this class's other sibling establish the same
 * "reuse, don't redeclare" idiom for exactly this reason. What genuinely differs about the Spring
 * context under test here is not the scan itself but the ACTIVE PROPERTY
 * ({@code lob.keri-attestation.enabled=true}, set below) — Spring Test's context cache keys on the
 * full merged configuration including properties, so this still gets its own distinct, independently
 * cached context from {@link DocumentVaultContextIntegrationTest}'s (which never sets that property).
 *
 * <p>That shared {@code TestConfig} already excludes {@code org.cardanofoundation.lob.app.keri_attestation.*}
 * from its broad scan (see its own javadoc) — so the broad scan still never reaches keri_attestation's
 * internals DIRECTLY, exactly like a real composing application never would. Flipping
 * {@code lob.keri-attestation.enabled=true} instead activates the real
 * {@code org.cardanofoundation.lob.app.config.KeriAttestationModuleConfig} entry point (still scanned
 * unconditionally: it lives under {@code org.cardanofoundation.lob.app.config}, a different package
 * than the excluded one), which is itself gated on that same property and whose OWN nested
 * {@code @ComponentScan(basePackages = "org.cardanofoundation.lob.app.keri_attestation")} is the sole
 * gate that then registers the module's real beans — including {@code CeremonyService}, the module's
 * {@link AttestationConsumptionApi} implementation.
 *
 * <p>{@code lob.keri-attestation.keria.url} is deliberately left UNSET: every keri_attestation bean
 * that touches a real KERIA connection ({@code SignifyClientConfig}'s beans, {@code KeriAgentService},
 * {@code KeriOobiService}, {@code KeriAuthBeginService}, {@code KeriCredentialService},
 * {@code KeriAttestService}, {@code KeriNotificationCorrelator},
 * {@code KeriAttestationController}) is gated on that narrower property (verified by reading each
 * class directly) and so stays off here — matching {@code CeremonyRepositoryTest}'s precedent inside
 * keri_attestation itself, which turns the module on the same way for its own repository tests.
 *
 * <p>{@code blockchain_publisher} is never imported here, directly or transitively:
 * document_vault's {@code build.gradle.kts} has no dependency on it at all (the dependency runs the
 * other way — blockchain_publisher depends on document_vault), so there is no
 * {@link AttestationFreezeGuard} implementation anywhere on this test's classpath by construction,
 * not merely by convention.
 */
@SpringBootTest(properties = "lob.keri-attestation.enabled=true")
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class DocumentVaultWithKeriNoPublisherContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private VaultDocumentService vaultDocumentService;

    @Test
    void contextRefreshSucceedsWithConsumptionApiPresentAndNoFreezeGuardImplementation() {
        // AttestationConsumptionApi: keri_attestation's CeremonyService, wired in via
        // KeriAttestationModuleConfig's own nested scan now that the flag is on.
        assertThat(context.getBeanNamesForType(AttestationConsumptionApi.class)).hasSize(1);

        // AttestationFreezeGuard: no implementation exists anywhere on this classpath at all -
        // blockchain_publisher, the only implementor, is not a document_vault dependency (compile or
        // test) in the first place.
        assertThat(context.getBeanNamesForType(AttestationFreezeGuard.class)).isEmpty();
    }

    @Test
    void vaultDocumentServicesFreezeGuardObjectProviderResolvesEmptyWithoutError() {
        // Behavioral pin for the exact seam VaultDocumentService#publish(docId, ceremonyId) consults
        // (via its private #consumeAttestation, immediately before AttestationConsumptionApi) whenever
        // a caller passes an attestationCeremonyId: ObjectProvider#getIfAvailable() must quietly return
        // null, not throw - proving VaultDocumentService's own dependency resolution for this
        // three-way flag combination is "fails closed at call time (ATTESTATION_UNAVAILABLE)", never
        // "fails to start".
        @SuppressWarnings("unchecked")
        ObjectProvider<AttestationFreezeGuard> freezeGuardProvider = (ObjectProvider<AttestationFreezeGuard>)
                ReflectionTestUtils.getField(vaultDocumentService, "attestationFreezeGuardProvider");

        assertThat(freezeGuardProvider).isNotNull();
        assertThat(freezeGuardProvider.getIfAvailable()).isNull();
    }
}
