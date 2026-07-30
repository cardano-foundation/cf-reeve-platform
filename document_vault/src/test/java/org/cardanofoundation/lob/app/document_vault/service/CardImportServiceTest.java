package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.ImportDestination;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.request.ImportCardRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.ImportCardResultView;
import org.cardanofoundation.lob.app.document_vault.repository.AddressbookEntryRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * The import is permissionless: there is no issuer and no signature, so
 * {@link KeyCardVerifier} is exercised for real here (it is trivial and dependency-free) rather than
 * mocked — these tests pin CardImportService's own behaviour, not a stubbed verifier.
 *
 * Alice is the caller throughout; Bob is therefore always somebody else. That is what makes the routing
 * rule observable: a card about Alice is her own key, a card about Bob is a contact.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardImportServiceTest {

    private static final String X25519_PUB = "a".repeat(64);

    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private AddressbookEntryRepository entryRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private CardAttestationDigestFactory attestationDigestFactory;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<
            org.cardanofoundation.lob.app.keri_attestation.service.AttestationImportVerifier> attestationVerifierProvider;
    @Mock
    private org.cardanofoundation.lob.app.keri_attestation.service.AttestationImportVerifier attestationVerifier;
    @Mock
    private org.springframework.beans.factory.ObjectProvider<
            org.cardanofoundation.lob.app.keri_attestation.service.CredentialVerificationService>
            verificationServiceProvider;
    @Mock
    private org.cardanofoundation.lob.app.keri_attestation.service.CredentialVerificationService
            verificationService;

    /** A recorded verdict for the card under test; only its presence matters to the guard. */
    private static final org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView
            VERDICT = new org.cardanofoundation.lob.app.keri_attestation.domain.view.CredentialAttestationView(
                    org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationStatus.VERIFIED,
                    java.time.Instant.now(), "EWalletAid", "ESchemaSaid", "Foundation Employee",
                    "EIssuer", "EIssuer",
                    org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel.STANDALONE,
                    "3", "EKelEventSaid", java.util.Map.of());

    private CardImportService service;

    @BeforeEach
    void setUp() {
        service = new CardImportService(keyRepository, entryRepository, securityHelper, organisationPublicApi,
                new KeyCardVerifier(), attestationDigestFactory, attestationVerifierProvider,
                verificationServiceProvider);
        // Attested-card tests exercise B1 persistence, not B2's verifier internals: stub the verifier to pass.
        when(attestationVerifierProvider.getIfAvailable()).thenReturn(attestationVerifier);
        when(attestationDigestFactory.digestOf(any())).thenReturn("EcardDigest");
        when(attestationVerifier.verify(any(), any())).thenReturn(io.vavr.control.Either.right(null));
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(securityHelper.getCurrentUserId()).thenReturn("sub-alice");
        when(securityHelper.getCurrentUser()).thenReturn("Alice Adams");
        when(organisationPublicApi.findByOrganisationId("org1"))
                .thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(entryRepository.findByOrganisationIdAndPublicKey(any(), any()))
                .thenReturn(Optional.empty());
        when(keyRepository.save(any(VaultKeyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entryRepository.save(any(AddressbookEntryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static final KeyCardDto.CardAttestation ATTESTATION = new KeyCardDto.CardAttestation(
            "https://example.org/oobi/EWalletAid/agent/EAgentEid", "EWalletAid", "ECredentialSaid",
            "ESchemaSaid", "3", "EKelEventSaid", "170", "ECardDigest", "EPayloadSaid",
            "-CESR-credential-chain-");

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

    private AddressbookEntryEntity savedEntry() {
        ArgumentCaptor<AddressbookEntryEntity> saved = ArgumentCaptor.forClass(AddressbookEntryEntity.class);
        verify(entryRepository).save(saved.capture());
        return saved.getValue();
    }

    private VaultKeyEntity savedKey() {
        ArgumentCaptor<VaultKeyEntity> saved = ArgumentCaptor.forClass(VaultKeyEntity.class);
        verify(keyRepository).save(saved.capture());
        return saved.getValue();
    }

    /** The headline requirement: adding a new recipient persists them for later use. */
    @Test
    void importingACardAboutSomeoneElseCreatesAnAddressbookContact() {
        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        assertEquals(ImportDestination.ADDRESSBOOK_ENTRY, result.get().destination());
        AddressbookEntryEntity entry = savedEntry();
        assertEquals("Bob Miller", entry.getDisplayName());
        assertEquals("bob@example.org", entry.getEmail());
        assertEquals(X25519_PUB, entry.getPublicKey());
        assertEquals(KeyAssurance.PORTABLE, entry.getAssurance());
        assertEquals("org1", entry.getOrganisationId());
        verify(keyRepository, never()).save(any());
    }

    /**
     * B1: a card carrying the indexer's attestation block persists it as provenance on the new
     * contact. Storage only — nothing here verifies the claim (that's B2).
     */
    @Test
    void importingACardWithAttestationPersistsItAsProvenanceOnTheNewContact() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setAttestation(ATTESTATION);

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isRight());
        AddressbookEntryEntity entry = savedEntry();
        assertEquals(ATTESTATION.oobi(), entry.getAttestationOobi());
        assertEquals(ATTESTATION.aid(), entry.getAttestationAid());
        assertEquals(ATTESTATION.credentialSaid(), entry.getAttestationCredentialSaid());
        assertEquals(ATTESTATION.schemaSaid(), entry.getAttestationSchemaSaid());
        assertEquals(ATTESTATION.kelSequence(), entry.getAttestationKelSequence());
        assertEquals(ATTESTATION.kelEventSaid(), entry.getAttestationKelEventSaid());
        assertEquals(ATTESTATION.metadataLabel(), entry.getAttestationMetadataLabel());
        assertEquals(ATTESTATION.cardDigest(), entry.getAttestationCardDigest());
        assertEquals(ATTESTATION.payloadSaid(), entry.getAttestationPayloadSaid());
    }

    /** A card without the optional block leaves the provenance columns NULL, same as today. */
    @Test
    void importingACardWithoutAttestationLeavesTheContactsAttestationColumnsNull() {
        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        assertNull(savedEntry().getAttestationAid());
    }

    /**
     * Provenance-once, same rule as {@link #reimportingAContactRefreshesDetailsButNeverProvenance()}:
     * an attestation claimed by a later re-import must never attach itself, after the fact, to a
     * contact that was first imported without one.
     */
    @Test
    void reimportingAnAlreadyKnownContactNeverAttachesALaterAttestationClaim() {
        AddressbookEntryEntity existing = new AddressbookEntryEntity();
        existing.setId("existing-entry");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setDisplayName("stale name");
        existing.setEmail("stale@example.org");
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setAttestation(ATTESTATION);

        service.importCard(request);

        assertNull(savedEntry().getAttestationAid());
    }

    /**
     * The key-substitution guard, now structural. The card claims a REEVE_ACCOUNT subject — Bob's real
     * Keycloak sub — but says nothing this backend can check: a card is unsigned, so subjectId is only
     * what the importer typed. It becomes a contact regardless, and a contact has no account id at all,
     * so it cannot be returned by any lookup of Bob's account and cannot join his wrap set.
     */
    @Test
    void aCardClaimingSomeoneElsesReeveAccountStillOnlyBecomesAContact() {
        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertEquals(ImportDestination.ADDRESSBOOK_ENTRY, result.get().destination());
        // nothing was written to the organisation key table — Bob's account is untouched
        verify(keyRepository, never()).save(any());
    }

    /** A card whose subject IS the caller is their own key: it binds to their account and reaches /keys/me. */
    @Test
    void importingOwnCardBindsTheKeyToTheCaller() {
        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-alice"));

        assertTrue(result.isRight());
        assertEquals(ImportDestination.ORG_KEY, result.get().destination());
        VaultKeyEntity key = savedKey();
        assertEquals("sub-alice", key.getAccountId()); // shows up in GET /keys/me
        assertEquals("org1", key.getOrganisationId());
        assertEquals(KeyOrigin.INDEXER_ISSUED, key.getOrigin());
        assertEquals(KeyAssurance.PORTABLE, key.getAssurance());
        // the name comes from the caller's own token, not the card: this is their account, and the token
        // is the only part of an import that is not self-asserted
        assertEquals("Alice Adams", key.getAccountName());
        verify(entryRepository, never()).save(any());
    }

    /** B1, own-key branch: the same attestation provenance persists on a self-import too. */
    @Test
    void importingOwnCardWithAttestationPersistsItAsProvenanceOnTheKey() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-alice");
        request.getCard().setAttestation(ATTESTATION);

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isRight());
        VaultKeyEntity key = savedKey();
        assertEquals(ATTESTATION.oobi(), key.getAttestationOobi());
        assertEquals(ATTESTATION.aid(), key.getAttestationAid());
        assertEquals(ATTESTATION.credentialSaid(), key.getAttestationCredentialSaid());
        assertEquals(ATTESTATION.schemaSaid(), key.getAttestationSchemaSaid());
        assertEquals(ATTESTATION.kelSequence(), key.getAttestationKelSequence());
        assertEquals(ATTESTATION.kelEventSaid(), key.getAttestationKelEventSaid());
        assertEquals(ATTESTATION.metadataLabel(), key.getAttestationMetadataLabel());
        assertEquals(ATTESTATION.cardDigest(), key.getAttestationCardDigest());
        assertEquals(ATTESTATION.payloadSaid(), key.getAttestationPayloadSaid());
    }

    // --- an attested row's signed identity fields cannot be rewritten by an unattested card ---

    private AddressbookEntryEntity attestedContact() {
        AddressbookEntryEntity existing = new AddressbookEntryEntity();
        existing.setId("existing-entry");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setDisplayName("Bob Miller");
        existing.setEmail("bob@example.org");
        existing.setDescription("Bob's audit key");
        existing.setAssurance(KeyAssurance.PORTABLE);
        existing.setAttestationAid("EWalletAid");
        return existing;
    }

    /**
     * The attack this guards: import a genuine attested card, then re-import an UNATTESTED card
     * carrying the same public key and attacker-chosen identity. Attestation provenance is written once
     * and never revisited, so without the guard the row would keep its verified anchor while every
     * field a reader looks at changed underneath it.
     */
    @Test
    void anUnattestedCardCannotRewriteAnAttestedContactsIdentityFields() {
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(attestedContact()));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Mallory", "mallory@evil.example", "org1"));

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.ATTESTED_CARD_FIELDS_IMMUTABLE, result.getLeft().getTitle());
        assertTrue(result.getLeft().getDetail().contains("displayName"));
        assertTrue(result.getLeft().getDetail().contains("email"));
        verify(entryRepository, never()).save(any());
    }

    /**
     * The gap between provenance and verdict. Attestation provenance is written only when a row is
     * CREATED, so a card first imported unattested and later re-imported with a verified attestation
     * earns a verdict while its attestationAid stays null. A guard reading only the column would treat
     * that row as unattested and let the next unattested import rewrite the fields the verdict covers.
     */
    @Test
    void aRecordedVerdictProtectsARowWhoseProvenanceColumnsAreStillNull() {
        AddressbookEntryEntity existing = attestedContact();
        existing.setAttestationAid(null);
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing));
        when(verificationServiceProvider.getIfAvailable()).thenReturn(verificationService);
        when(verificationService.find("org1", X25519_PUB)).thenReturn(Optional.of(VERDICT));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Mallory", "mallory@evil.example", "org1"));

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.ATTESTED_CARD_FIELDS_IMMUTABLE, result.getLeft().getTitle());
        verify(entryRepository, never()).save(any());
    }

    /** A re-import that changes nothing stays the ordinary no-op it looks like. */
    @Test
    void reimportingAnIdenticalUnattestedCardOverAnAttestedContactIsStillAllowed() {
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(attestedContact()));

        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
    }

    /** A card carrying its own verified attestation covers these fields by digest, so it may update them. */
    @Test
    void anAttestedCardMayUpdateAnAttestedContactsIdentityFields() {
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(attestedContact()));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Robert Miller", "robert@example.org", "org1"));
        request.getCard().setAttestation(ATTESTATION);

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isRight(), () -> result.isLeft() ? result.getLeft().getDetail() : "");
        assertEquals("Robert Miller", savedEntry().getDisplayName());
    }

    /** An UNattested row is ordinary trust-on-first-use: nothing signed its fields, so it stays mutable. */
    @Test
    void anUnattestedContactsFieldsRemainFreelyUpdatable() {
        AddressbookEntryEntity existing = attestedContact();
        existing.setAttestationAid(null);
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Renamed", "renamed@example.org", "org1"));

        assertTrue(service.importCard(request).isRight());
        assertEquals("Renamed", savedEntry().getDisplayName());
    }

    @Test
    void anUnattestedCardCannotRelabelAnAttestedOwnKey() {
        VaultKeyEntity existing = new VaultKeyEntity();
        existing.setId("existing-key");
        existing.setAccountId("sub-alice");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setLabel("Attested label");
        existing.setOrigin(KeyOrigin.INDEXER_ISSUED);
        existing.setAssurance(KeyAssurance.PORTABLE);
        existing.setAttestationAid("EWalletAid");
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("sub-alice", "org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-alice"));

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.ATTESTED_CARD_FIELDS_IMMUTABLE, result.getLeft().getTitle());
        verify(keyRepository, never()).save(any());
    }

    /**
     * Provenance-once on the key table too: re-importing a card that now claims an attestation must
     * not attach it to a key row that already existed without one — origin/assurance already follow
     * this rule (see the class javadoc and {@code CardImportService#importOwnKey}).
     */
    @Test
    void reimportingAnAlreadyKnownKeyNeverAttachesALaterAttestationClaim() {
        VaultKeyEntity existing = new VaultKeyEntity();
        existing.setId("existing-key");
        existing.setAccountId("sub-alice");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setLabel("stale label");
        existing.setOrigin(KeyOrigin.INDEXER_ISSUED);
        existing.setAssurance(KeyAssurance.PORTABLE);
        when(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("sub-alice", "org1", X25519_PUB))
                .thenReturn(Optional.of(existing));
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-alice");
        request.getCard().setAttestation(ATTESTATION);

        service.importCard(request);

        assertNull(savedKey().getAttestationAid());
    }

    /**
     * The reported bug, end to end. A card minted outside Reeve names its holder's own organisation as a
     * human label — "Privat" — and an outside issuer cannot know the importing org's hex id. Requiring a
     * match rejected the card with 422 CARD_ORG_MISMATCH and made the addressbook useless for exactly the
     * external contacts it exists to hold. The card's organisation is kept as provenance, never compared.
     */
    @Test
    void aCardFromOutsideReeveImportsAndKeepsItsHolderOrganisation() {
        ImportCardRequest request = new ImportCardRequest();
        request.setOrganisationId("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94");
        request.setCard(card(CardSubjectType.EXTERNAL, "indexer-uuid-1", "Privat"));
        when(securityHelper.canUserAccessOrg("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .thenReturn(true);
        when(organisationPublicApi
                .findByOrganisationId("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94"))
                .thenReturn(Optional.of(new Organisation()));

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isRight());
        AddressbookEntryEntity entry = savedEntry();
        assertEquals("Privat", entry.getHomeOrganisationId()); // kept as provenance
        // the addressbook it landed in is the REQUEST's organisation, never the card's
        assertEquals("75f95560c1d883ee7628993da5adf725a5d97a13929fd4f477be0faf5020ca94",
                entry.getOrganisationId());
    }

    /** An external tool has no organisation to name for its holder, so the field is optional. */
    @Test
    void aCardWithoutAHolderOrganisationImports() {
        ImportCardRequest request = new ImportCardRequest();
        request.setOrganisationId("org1");
        request.setCard(card(CardSubjectType.EXTERNAL, "indexer-uuid-1", null));

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isRight());
        assertNull(savedEntry().getHomeOrganisationId());
    }

    /** Re-adding a recipient is a normal thing for a user to do, not an error. */
    @Test
    void reimportingTheSameCardUpdatesTheContactInPlaceInsteadOfDuplicating() {
        AddressbookEntryEntity existing = new AddressbookEntryEntity();
        existing.setId("existing-entry");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setDisplayName("stale name");
        existing.setEmail("stale@example.org");
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isRight());
        assertEquals("existing-entry", result.get().entry().entryId()); // same row, no duplicate
        assertEquals("bob@example.org", result.get().entry().email()); // refreshed from the card
        assertEquals("Bob Miller", result.get().entry().displayName());
    }

    /**
     * Provenance records what a card claimed when a contact first entered the addressbook. Letting a
     * later import rewrite the origin a sender already verified out-of-band would defeat the point of
     * showing it, and a PORTABLE key must never quietly become PASSKEY.
     */
    @Test
    void reimportingAContactRefreshesDetailsButNeverProvenance() {
        AddressbookEntryEntity existing = new AddressbookEntryEntity();
        existing.setId("existing-entry");
        existing.setOrganisationId("org1");
        existing.setPublicKey(X25519_PUB);
        existing.setDisplayName("stale name");
        existing.setEmail("stale@example.org");
        existing.setAssurance(KeyAssurance.PASSKEY);
        existing.setHomeOrganisationId("Privat");
        when(entryRepository.findByOrganisationIdAndPublicKey("org1", X25519_PUB))
                .thenReturn(Optional.of(existing));

        service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        AddressbookEntryEntity saved = savedEntry();
        // the incoming card says PORTABLE and names "org1" — neither may move
        assertEquals(KeyAssurance.PASSKEY, saved.getAssurance());
        assertEquals("Privat", saved.getHomeOrganisationId());
        // only what the card says about the person refreshes
        assertEquals("Bob Miller", saved.getDisplayName());
        assertEquals("bob@example.org", saved.getEmail());
    }

    @Test
    void aRejectedCardWritesNothing() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        // an unsupported version -> verifier rejects. (This used to lean on an org mismatch, back when
        // the card's organisation was a gate; the point of the test is the write, not the reason.)
        request.getCard().setV(2);

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION, result.getLeft().getTitle());
        verify(entryRepository, never()).save(any());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void aCardCarryingAPrivateKeyWritesNothing() {
        ImportCardRequest request = request(CardSubjectType.REEVE_ACCOUNT, "sub-bob");
        request.getCard().putUnknown("privateKey", java.util.Map.of("wrapped", "deadbeef"));

        Either<ProblemDetail, ImportCardResultView> result = service.importCard(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_CONTAINS_PRIVATE_KEY, result.getLeft().getTitle());
        verify(entryRepository, never()).save(any());
        verify(keyRepository, never()).save(any());
    }

    @Test
    void importIntoAForeignOrganisationIsForbidden() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Either<ProblemDetail, ImportCardResultView> result =
                service.importCard(request(CardSubjectType.REEVE_ACCOUNT, "sub-bob"));

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
        verify(entryRepository, never()).save(any());
        verify(keyRepository, never()).save(any());
    }

}
