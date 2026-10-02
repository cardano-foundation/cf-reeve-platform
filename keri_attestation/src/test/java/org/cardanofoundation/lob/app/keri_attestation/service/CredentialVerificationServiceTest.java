package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationStatus;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.CredentialVerificationEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView;
import org.cardanofoundation.lob.app.keri_attestation.repository.CredentialVerificationRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.CredentialChainValidator.ValidatedCredential;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CredentialVerificationServiceTest {

    private static final String ORG = "org1";
    private static final String PUB = "a".repeat(64);

    @Mock
    private CredentialVerificationRepository repository;

    private CredentialVerificationService service;

    @BeforeEach
    void setUp() {
        service = new CredentialVerificationService(repository, new ObjectMapper());
        when(repository.save(any(CredentialVerificationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ValidatedCredential credential() {
        return new ValidatedCredential("ECredSaid", "ESchemaSaid", "vLEI Legal Entity", "EQviIssuer",
                "EGleifRoot", TrustModel.CHAINED, Map.of("LEI", "5493001KJTIIGC8Y1R12"), "fp-123");
    }

    private CredentialVerificationEntity saved() {
        ArgumentCaptor<CredentialVerificationEntity> captor =
                ArgumentCaptor.forClass(CredentialVerificationEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordingAVerificationStoresTheVerdictAndWhyItWasReached() {
        when(repository.findByOrganisationIdAndPublicKey(ORG, PUB)).thenReturn(Optional.empty());

        service.recordVerified(ORG, PUB, "EAttesterAid", credential(), "3", "EKelEvent", "fp-123");

        CredentialVerificationEntity row = saved();
        assertEquals(AttestationStatus.VERIFIED, row.getStatus());
        assertEquals("vLEI Legal Entity", row.getSchemaName());
        // Issuer and anchor are recorded SEPARATELY: in a chained credential they are different AIDs,
        // and collapsing them would misstate both who issued it and why it was believed.
        assertEquals("EQviIssuer", row.getLeafIssuerAid());
        assertEquals("EGleifRoot", row.getTrustAnchorAid());
        assertEquals(TrustModel.CHAINED, row.getTrustModel());
        assertEquals("fp-123", row.getPolicyFingerprint());
        assertEquals("EKelEvent", row.getKelEventSaid());
        assertTrue(row.getVerifiedAt() != null);
    }

    /** Re-verifying updates the existing row rather than accumulating a second verdict for one card. */
    @Test
    void reverifyingTheSameCardUpdatesTheExistingRow() {
        CredentialVerificationEntity existing = new CredentialVerificationEntity();
        existing.setId("existing-id");
        existing.setOrganisationId(ORG);
        existing.setPublicKey(PUB);
        existing.setStatus(AttestationStatus.UNKNOWN_LEGACY);
        when(repository.findByOrganisationIdAndPublicKey(ORG, PUB)).thenReturn(Optional.of(existing));

        service.recordVerified(ORG, PUB, "EAttesterAid", credential(), "3", "EKelEvent", "fp-123");

        CredentialVerificationEntity row = saved();
        assertEquals("existing-id", row.getId());
        assertEquals(AttestationStatus.VERIFIED, row.getStatus());
    }

    @Test
    void claimsRoundTripThroughStorage() {
        when(repository.findByOrganisationIdAndPublicKey(ORG, PUB)).thenReturn(Optional.empty());
        service.recordVerified(ORG, PUB, "EAttesterAid", credential(), "3", "EKelEvent", "fp-123");
        CredentialVerificationEntity stored = saved();
        when(repository.findByOrganisationIdAndPublicKey(ORG, PUB)).thenReturn(Optional.of(stored));

        CredentialAttestationView view = service.find(ORG, PUB).orElseThrow();

        assertEquals(Map.of("LEI", "5493001KJTIIGC8Y1R12"), view.claims());
        assertEquals("vLEI Legal Entity", view.schemaName());
    }

    /** Unreadable stored claims must degrade to none, not break the read for the whole page. */
    @Test
    void unreadableStoredClaimsDegradeToEmptyRatherThanFailing() {
        CredentialVerificationEntity row = new CredentialVerificationEntity();
        row.setId("id");
        row.setOrganisationId(ORG);
        row.setPublicKey(PUB);
        row.setStatus(AttestationStatus.VERIFIED);
        row.setVerifiedAt(java.time.Instant.now());
        row.setClaims("{ not json");
        when(repository.findByOrganisationIdAndPublicKey(ORG, PUB)).thenReturn(Optional.of(row));

        assertEquals(Map.of(), service.find(ORG, PUB).orElseThrow().claims());
    }

    @Test
    void aBulkReadIsKeyedByPublicKeyAndAsksTheRepositoryOnce() {
        CredentialVerificationEntity row = new CredentialVerificationEntity();
        row.setId("id");
        row.setOrganisationId(ORG);
        row.setPublicKey(PUB);
        row.setStatus(AttestationStatus.VERIFIED);
        row.setVerifiedAt(java.time.Instant.now());
        when(repository.findByOrganisationIdAndPublicKeyIn(ORG, List.of(PUB))).thenReturn(List.of(row));

        Map<String, CredentialAttestationView> byKey = service.findAll(ORG, List.of(PUB));

        assertEquals(1, byKey.size());
        assertEquals(AttestationStatus.VERIFIED, byKey.get(PUB).status());
        org.mockito.Mockito.verify(repository).findByOrganisationIdAndPublicKeyIn(ORG, List.of(PUB));
    }

    @Test
    void anEmptyBulkReadTouchesNoRepository() {
        assertEquals(Map.of(), service.findAll(ORG, List.of()));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findByOrganisationIdAndPublicKeyIn(any(), any());
    }
}
