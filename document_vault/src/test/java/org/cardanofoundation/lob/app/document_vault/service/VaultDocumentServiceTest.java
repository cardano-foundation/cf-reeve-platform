package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentSharedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
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
}
