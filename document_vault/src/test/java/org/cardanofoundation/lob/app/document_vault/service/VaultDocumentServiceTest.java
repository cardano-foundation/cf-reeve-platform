package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentEnvelopeView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class VaultDocumentServiceTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String HEX96 = "b".repeat(96);
    private static final String HEX24 = "c".repeat(24);
    private static final byte[] CIPHERTEXT = "not-really-encrypted-bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private VaultDocumentRepository documentRepository;
    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private KeycloakSecurityHelper securityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private KeyCardVerifier cardVerifier;
    @Mock
    private ObjectProvider<IpfsAvailability> ipfsAvailability;

    @InjectMocks
    private VaultDocumentService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 10_485_760L);
        ReflectionTestUtils.setField(service, "maxSlots", 64);
        // lenient: STRICT_STUBS would fail early-return tests that never consume these
        lenient().when(securityHelper.getCurrentUserId()).thenReturn("sender");
        lenient().when(securityHelper.canUserAccessOrg("org1")).thenReturn(true);
        // default: issuers are trusted. Mockito's `false` would make every slot key look de-trusted
        // and fail the upload tests for a reason that has nothing to do with what they test.
        lenient().when(cardVerifier.isTrustedIssuer(any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        // hasAdminRole() reads SecurityContextHolder directly; a test that populates it must not
        // let that authentication leak into the next test in this class (or another class).
        SecurityContextHolder.clearContext();
    }

    /** The stale-client window: the addressbook was cached before the issuer was de-trusted. */
    @Test
    void uploadRejectsASlotWrappedToAKeyFromADeTrustedIssuer() {
        VaultKeyEntity hostile = orgKey("k-s", "sender", "org1");
        hostile.setOrigin(KeyOrigin.INDEXER_ISSUED);
        hostile.setIssuerId("compromised-issuer");
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(hostile));
        when(cardVerifier.isTrustedIssuer("compromised-issuer")).thenReturn(false);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
        verify(documentRepository, never()).save(any());
    }

    private VaultKeyEntity orgKey(String id, String accountId, String org) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId(org);
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey("e".repeat(64));
        key.setLabel("k");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    private UploadDocumentRequest request() {
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setOrganisationId("org1");
        request.setEnvelopeVersion(1);
        request.setFileName("q3-report.pdf");
        request.setPlaintextHash(HEX64);
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder().encodeToString(CIPHERTEXT));
        payload.setNonce(HEX24);
        request.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot1 = new UploadDocumentRequest.SlotRequest();
        slot1.setKeyId("k-s");
        slot1.setRecipientRef("me");
        slot1.setEphemeralPub(HEX64);
        slot1.setWrappedDek(HEX96);
        UploadDocumentRequest.SlotRequest slot2 = new UploadDocumentRequest.SlotRequest();
        slot2.setKeyId("k-r");
        slot2.setRecipientRef("Bob");
        slot2.setEphemeralPub(HEX64);
        slot2.setWrappedDek(HEX96);
        request.setSlots(List.of(slot1, slot2));
        return request;
    }

    @Test
    void uploadPersistsEnvelopeAndPublishesMinimizedEvent() throws Exception {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1"),
                orgKey("k-r", "recipient", "org1")));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isRight());
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CIPHERTEXT));
        assertEquals(expectedHash, result.get().contentHash());

        ArgumentCaptor<VaultDocumentEntity> saved = ArgumentCaptor.forClass(VaultDocumentEntity.class);
        verify(documentRepository).save(saved.capture());
        assertEquals(2, saved.getValue().getSlots().size());
        assertEquals(CIPHERTEXT.length, saved.getValue().getSizeBytes());

        ArgumentCaptor<DocumentSharedEvent> event = ArgumentCaptor.forClass(DocumentSharedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(Set.of("sender", "recipient"), event.getValue().recipientAccountIds());
        assertEquals("org1", event.getValue().organisationId());
    }

    @Test
    void uploadRejectsUnknownSlotKey() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        // slot k-r references a key that does not exist in the directory
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsSlotKeyOfAnotherOrganisation() {
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(new Organisation()));
        when(keyRepository.findAllById(any())).thenReturn(List.of(
                orgKey("k-s", "sender", "org1"),
                orgKey("k-r", "recipient", "other-org")));

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.SLOT_KEY_INVALID, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsUnknownEnvelopeVersion() {
        UploadDocumentRequest request = request();
        request.setEnvelopeVersion(2);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request);

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.UNSUPPORTED_ENVELOPE_VERSION, result.getLeft().getTitle());
    }

    @Test
    void uploadRejectsOversizedCiphertext() {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 10L);

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request());

        assertTrue(result.isLeft());
        assertEquals(413, result.getLeft().getStatus());
    }

    @Test
    void uploadRejectsInvalidBase64() {
        UploadDocumentRequest request = request();
        request.getPayload().setCiphertext("!!!not-base64!!!");

        Either<ProblemDetail, DocumentUploadedView> result = service.upload(request);

        assertTrue(result.isLeft());
        assertEquals(400, result.getLeft().getStatus());
    }

    @Test
    void listRequiresMembership() {
        when(securityHelper.canUserAccessOrg("org1")).thenReturn(false);

        assertTrue(service.list("org1", null, null, null, Pageable.unpaged()).isLeft());
    }

    /**
     * Controller-mandated fix (Task 3 review): a filename/description containing a literal
     * {@code %} or {@code _} must be searchable, and a bare {@code %} query must NOT act as a
     * wildcard matching every document. The service escapes LIKE metacharacters before binding
     * {@code q} into {@code VaultDocumentRepository.search} — this pins that the escaped form,
     * not the raw user input, is what reaches the query.
     */
    @Test
    void listEscapesLikeMetacharactersInFreeTextQueryBeforeSearching() {
        when(documentRepository.search(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        service.list("org1", null, null, "50%off_deal", Pageable.unpaged());

        ArgumentCaptor<String> qCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).search(eq("org1"), any(), any(), any(), qCaptor.capture(), any());
        assertEquals("50\\%off\\_deal", qCaptor.getValue());
    }

    @Test
    void deleteByNonCreatorWithoutAdminRoleIsRejected() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("someone-else");
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.NOT_DOCUMENT_CREATOR, problem.get().getTitle());
    }

    /**
     * The admin bypass in {@code hasAdminRole()}: a Keycloak-authenticated caller holding
     * {@code ROLE_<keycloak.roles.admin>} (default "admin") may delete a DRAFT they did not
     * create. This is the previously untested branch flagged in review.
     */
    @Test
    void deleteByNonCreatorWithAdminRoleSucceeds() {
        ReflectionTestUtils.setField(service, "adminRoleName", "admin"); // matches keycloak.roles.admin default
        TestingAuthenticationToken adminAuth = new TestingAuthenticationToken("admin-user", null, "ROLE_admin");
        adminAuth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(adminAuth);

        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("someone-else"); // securityHelper.getCurrentUserId() stubbed to "sender"
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isEmpty());
        verify(documentRepository).delete(doc);
    }

    @Test
    void deleteOutsideOwnOrganisationIsRejectedEvenForCreator() {
        // Keycloak admin roles are realm-wide; membership of the document's org is checked separately
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("other-org");
        doc.setCreatedByAccount("sender");
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(403, problem.get().getStatus());
    }

    @Test
    void deleteByCreatorSucceeds() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("sender");
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isEmpty());
        verify(documentRepository).delete(doc);
    }

    @Test
    void deleteOnPublishedDocumentIsRejectedEvenForCreator() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setCreatedByAccount("sender");
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));

        Optional<ProblemDetail> problem = service.delete("doc1");

        assertTrue(problem.isPresent());
        assertEquals(VaultProblems.DOCUMENT_PUBLISHED_IMMUTABLE, problem.get().getTitle());
    }

    @Test
    void fetchByCreatorReturnsEnvelopeWithCiphertext() {
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertTrue(result.get().envelopeAccessible());
        assertEquals(Base64.getEncoder().encodeToString(CIPHERTEXT), result.get().payload().ciphertext());
        assertEquals(1, result.get().slots().size());
        assertEquals("k-s", result.get().slots().get(0).keyId());
        assertEquals("k-s", result.get().recipients().get(0).keyId());
    }

    @Test
    void fetchByRecipientReturnsEnvelope() {
        when(securityHelper.getCurrentUserId()).thenReturn("recipient");
        VaultDocumentEntity doc = draftDoc(); // created by "sender", slot references key k-s
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "recipient", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertTrue(result.get().envelopeAccessible());
        assertNotNull(result.get().payload());
    }

    /**
     * The access change: an org member who is neither creator nor recipient still gets the detail
     * page — the org-wide list already told them the document exists — but NEITHER the ciphertext
     * NOR the slots. The slots carry wrapped DEKs; a draft is not public, so there is no reason to
     * hand them to someone who cannot use them.
     */
    @Test
    void fetchByOtherOrgMemberReturnsMetadataWithoutTheEnvelope() {
        when(securityHelper.getCurrentUserId()).thenReturn("stranger");
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(keyRepository.findAllById(any())).thenReturn(List.of(orgKey("k-s", "sender", "org1")));

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isRight());
        assertFalse(result.get().envelopeAccessible());
        assertNull(result.get().payload());
        assertNull(result.get().slots(), "wrapped DEKs must not reach a non-participant");
        // metadata and "who can read this" stay visible — they are org-visible by design
        assertEquals("q3-report.pdf", result.get().fileName());
        assertEquals(1, result.get().recipients().size());
        assertEquals("sender", result.get().recipients().get(0).accountId());
        assertEquals("k-s", result.get().recipients().get(0).keyId());
    }

    @Test
    void fetchOutsideOwnOrganisationIs404() {
        VaultDocumentEntity doc = draftDoc();
        doc.setOrganisationId("other-org");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(securityHelper.canUserAccessOrg("other-org")).thenReturn(false);

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("doc1");

        assertTrue(result.isLeft());
        assertEquals(404, result.getLeft().getStatus());
    }

    @Test
    void fetchUnknownDocumentIs404() {
        when(documentRepository.findById("nope")).thenReturn(Optional.empty());

        Either<ProblemDetail, DocumentEnvelopeView> result = service.fetch("nope");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.DOCUMENT_NOT_FOUND, result.getLeft().getTitle());
    }

    @Test
    void publishLocksDocumentAndFiresPiiFreeCommand() {
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ipfsAvailability.getIfAvailable()).thenReturn(() -> true);

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isRight());
        assertEquals(VaultDocumentStatus.PUBLISHED, result.get().status());
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, result.get().ledgerDispatchStatus());

        ArgumentCaptor<DocumentPublishCommand> command = ArgumentCaptor.forClass(DocumentPublishCommand.class);
        verify(eventPublisher).publishEvent(command.capture());
        assertEquals("doc1", command.getValue().documentId());
        assertEquals(1, command.getValue().slots().size());
        // PII-free: the record has no email/label/filename fields at all; belt-and-braces on the serialised form
        assertFalse(command.getValue().toString().contains("q3-report.pdf"));
        assertFalse(command.getValue().toString().contains("canary-recipient-label"));
    }

    /** Auth-first ordering (finding 2): org access and document/status checks now run before the
     *  IPFS capability probe, so this test must supply a document (org member, DRAFT) to reach it. */
    @Test
    void publishRejectedWhenIpfsUnavailable() {
        VaultDocumentEntity doc = draftDoc();
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));
        when(ipfsAvailability.getIfAvailable()).thenReturn(null);

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isLeft());
        assertEquals(503, result.getLeft().getStatus());
        assertEquals(VaultProblems.DOCUMENT_PUBLISHING_UNAVAILABLE, result.getLeft().getTitle());
    }

    @Test
    void publishRejectedWhenAlreadyPublished() {
        VaultDocumentEntity doc = draftDoc();
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.ALREADY_PUBLISHED, result.getLeft().getTitle());
    }

    /** Finding 2: a non-member (or someone probing a random documentId) must not learn whether IPFS
     *  is configured in this deployment. The org check must reject BEFORE the IPFS probe even runs. */
    @Test
    void publishChecksOrgAccessBeforeProbingIpfsAvailability() {
        VaultDocumentEntity doc = draftDoc();
        doc.setOrganisationId("org-not-mine");
        when(documentRepository.findByIdForUpdate("doc1")).thenReturn(Optional.of(doc));
        // securityHelper.canUserAccessOrg("org-not-mine") is unstubbed -> defaults to false

        Either<ProblemDetail, DocumentView> result = service.publish("doc1");

        assertTrue(result.isLeft());
        assertEquals(403, result.getLeft().getStatus());
        verify(ipfsAvailability, never()).getIfAvailable();
    }

    private VaultDocumentEntity draftDoc() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setEnvelopeVersion(1);
        doc.setStatus(VaultDocumentStatus.DRAFT);
        doc.setContentHash("a".repeat(64));
        doc.setPlaintextHash("a".repeat(64));
        doc.setCiphertext(CIPHERTEXT);
        doc.setPayloadNonce(HEX24);
        doc.setFileName("q3-report.pdf");
        doc.setSizeBytes(CIPHERTEXT.length);
        doc.setCreatedByAccount("sender");
        doc.setSlots(List.of(new DocumentSlot("k-s", "canary-recipient-label", HEX64, HEX96)));
        return doc;
    }
}
