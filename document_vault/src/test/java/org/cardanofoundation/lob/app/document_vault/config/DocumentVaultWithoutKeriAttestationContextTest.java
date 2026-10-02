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

/**
 * The other half of the deployment matrix: {@code document_vault=on} with
 * {@code keri-attestation=off} — the plain, unattested publish path, which is the default and by far
 * the common case.
 *
 * <p>Here {@code DocumentVaultAttestationConfig} is switched off by its own
 * {@code @ConditionalOnProperty}, so no {@link AttestationFreezeGuard} exists. That must remain a
 * quiet, working configuration: {@code VaultDocumentService}'s {@code ObjectProvider} resolves empty
 * and an attested publish fails closed at call time with {@code ATTESTATION_UNAVAILABLE} — it must
 * never fail to START, or turning attestation off would take the whole vault down with it.
 *
 * <p>Supersedes {@code DocumentVaultWithKeriNoPublisherContextTest}, which asserted that no freeze
 * guard existed even with keri-attestation ON. That was true of the old architecture and was precisely
 * the bug: the guard and the attestation provider lived in {@code blockchain_publisher}, so the pod
 * that runs ceremonies never had them. {@link ApiPodDocumentAttestationContextTest} now covers the
 * keri-ON case and asserts the opposite.
 */
@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class DocumentVaultWithoutKeriAttestationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private VaultDocumentService vaultDocumentService;

    @Test
    void noFreezeGuardExistsWhenAttestationIsDisabled() {
        assertThat(context.getBeanNamesForType(AttestationFreezeGuard.class)).isEmpty();
    }

    @Test
    void theFreezeGuardObjectProviderResolvesEmptyWithoutError() {
        // Behavioural pin for the seam VaultDocumentService#publish consults whenever a caller passes an
        // attestationCeremonyId: getIfAvailable() must quietly return null rather than throw.
        @SuppressWarnings("unchecked")
        ObjectProvider<AttestationFreezeGuard> freezeGuardProvider = (ObjectProvider<AttestationFreezeGuard>)
                ReflectionTestUtils.getField(vaultDocumentService, "attestationFreezeGuardProvider");

        assertThat(freezeGuardProvider).isNotNull();
        assertThat(freezeGuardProvider.getIfAvailable()).isNull();
    }
}
