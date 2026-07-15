package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.VaultKeyView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * The import is permissionless (contract §2.8, amended): there is no issuer and no signature, so
 * {@link KeyCardVerifier} is exercised for real here (it is trivial and dependency-free) rather
 * than mocked — these tests pin CardImportService's own behaviour, not a stubbed verifier.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardImportServiceTest {

    private static final String X25519_PUB = "a".repeat(64);

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    private CardImportService service;

    @BeforeEach
    void setUp() {
        service = new CardImportService(keyRepository, securityHelper, organisationPublicApi, new KeyCardVerifier());
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId("org1"))
                .thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(keyRepository.save(any(VaultKeyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private KeyCardDto card(CardSubjectType subjectType, String subjectId, String organisationId) {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(subjectType, subjectId, "Bob Miller",
                "bob@example.org", organisationId));
        card.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        return card;
    }

    private ImportCardRequest request(CardSubjectType subjectType, String subjectId) {
        ImportCardRequest request = new ImportCardRequest();
        request.setOrganisationId("org1");
        request.setCard(card(subjectType, subjectId, "org1"));
        return request;
    }

    /** The headline requirement: adding a new recipient persists them for later use. */
    @Test
    void importingAContactCardCreatesAnAddressbookEntryForTheHolder() {
        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        // the SUBJECT owns the key, not the importer
        assertEquals("sub-bob", saved.getValue().getAccountId());
        assertEquals("bob@example.org", saved.getValue().getEmail());
        assertEquals(KeyOrigin.INDEXER_ISSUED, saved.getValue().getOrigin());
        assertEquals(KeyAssurance.PORTABLE, saved.getValue().getAssurance());
        assertFalse(saved.getValue().isExternal());
        assertEquals("sub-bob", result.get().accountId());
        assertEquals("Bob Miller", result.get().accountName());
    }

    @Test
    void anExternalHolderIsMarkedExternal() {
        service.importCard(request(CardSubjectType.EXTERNAL, "indexer-uuid-1"));

        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        assertTrue(saved.getValue().isExternal());
        assertEquals("indexer-uuid-1", saved.getValue().getAccountId());
    }

    /** A card whose subject IS the caller lands in their own keychain — no branch, the subject decides. */
    @Test
    void importingOwnCardBindsTheKeyToTheCaller() {
        service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-alice"));

        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        assertEquals("sub-alice", saved.getValue().getAccountId()); // shows up in GET /keys/me
    }

    /** Re-adding a recipient is a normal thing for a user to do, not an error. */
    @Test
    void reimportingTheSameCardUpdatesInPlaceInsteadOfDuplicating() {
        VaultKeyEntity existing = new VaultKeyEntity();
        existing.setId("existing-key");
        existing.setAccountId("sub-bob");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setLabel("stale label");
        existing.setEmail("stale@example.org");
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("sub-bob", "org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        assertEquals("existing-key", result.get().keyId()); // same row, no duplicate
        assertEquals("bob@example.org", result.get().email()); // refreshed from the card
    }

    /**
     * Contract §2.8.5: re-importing an existing row refreshes ONLY label/email. Provenance
     * (origin, assurance, external) must never move on a re-import — a PORTABLE key must never
     * silently upgrade to PASSKEY, and a SELF_ENROLLED row must never flip to INDEXER_ISSUED, just
     * because a card for the same public key came in.
     */
    @Test
    void reimportingAnExistingRowRefreshesOnlyLabelAndEmailNotProvenance() {
        VaultKeyEntity existing = new VaultKeyEntity();
        existing.setId("existing-key");
        existing.setAccountId("sub-bob");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setAccountName("Bob M.");
        existing.setLabel("stale label");
        existing.setEmail("stale@example.org");
        existing.setOrigin(KeyOrigin.SELF_ENROLLED);
        existing.setAssurance(KeyAssurance.PASSKEY);
        existing.setExternal(false);
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("sub-bob", "org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        // provenance is untouched by a re-import
        assertEquals(KeyOrigin.SELF_ENROLLED, saved.getValue().getOrigin());
        assertEquals(KeyAssurance.PASSKEY, saved.getValue().getAssurance());
        assertFalse(saved.getValue().isExternal());
        assertEquals("Bob M.", saved.getValue().getAccountName());
        // only label/email refresh from the card
        assertEquals("Bob's audit key", saved.getValue().getLabel());
        assertEquals("bob@example.org", saved.getValue().getEmail());
    }

    @Test
    void aRejectedCardWritesNothing() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        // org mismatch between the card's subject and the request organisation -> verifier rejects
        request.setCard(card(CardSubjectType.REEVE_ACCOUNT, "sub-bob", "other-org"));

        Either<ProblemDetail, VaultKeyView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_ORG_MISMATCH, result.getLeft().getTitle());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void aCardCarryingAPrivateKeyWritesNothing() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().putUnknown("privateKey", java.util.Map.of("wrapped", "deadbeef"));

        Either<ProblemDetail, VaultKeyView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_CONTAINS_PRIVATE_KEY, result.getLeft().getTitle());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void importIntoAForeignOrganisationIsForbidden() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Either<ProblemDetail, VaultKeyView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
        verify(keyRepository, never()).save(any());
    }
}
