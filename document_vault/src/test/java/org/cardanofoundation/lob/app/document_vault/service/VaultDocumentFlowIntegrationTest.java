package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.ResolveRecipientsRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentUploadedView;
import org.cardanofoundation.lob.app.document_vault.domain.view.RecipientKeyView;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class VaultDocumentFlowIntegrationTest {

    private static final String ORG_ID = "org-flow-test";

    @Autowired
    private VaultKeyService keyService;
    @Autowired
    private RecipientResolutionService resolutionService;
    @Autowired
    private VaultDocumentService documentService;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private EntityManager em;

    @BeforeEach
    void organisation() {
        // every NOT NULL column of the organisation table must be set (V1.0_100_3 migration)
        organisationRepository.saveAndFlush(Organisation.builder()
                .id(ORG_ID)
                .name("Flow Test Org")
                .taxIdNumber("TAX-1")
                .countryCode("CH")
                .accountPeriodDays(365)
                .currencyId("ISO_4217:CHF")
                .reportCurrencyId("ISO_4217:CHF")
                .phoneNumber("+41 000 000 000")
                .city("Zug")
                .postCode("6300")
                .province("ZG")
                .address("Test Street 1")
                .adminEmail("admin@example.org")
                .build());
    }

    @Test
    void fullCreatingFlow() {
        // 1. enroll a key
        RegisterKeyRequest keyRequest = new RegisterKeyRequest();
        keyRequest.setOrganisationId(ORG_ID);
        keyRequest.setLabel("laptop");
        keyRequest.setPublicKey("a".repeat(64));
        keyRequest.setEmail("system@example.org");
        String keyId = keyService.registerKey(keyRequest).get().keyId();

        // 2. resolve (sender auto-included -> self key returned)
        ResolveRecipientsRequest resolve = new ResolveRecipientsRequest();
        resolve.setOrganisationId(ORG_ID);
        resolve.setRecipientAccountIds(List.of("system"));
        List<RecipientKeyView> targets = resolutionService.resolve(resolve).get();
        assertEquals(1, targets.size());
        assertEquals(keyId, targets.get(0).keyId());

        // 3. upload an envelope wrapped to the resolved key
        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setOrganisationId(ORG_ID);
        upload.setEnvelopeVersion(1);
        upload.setFileName("statement.pdf");
        upload.setPlaintextHash("0".repeat(64));
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder()
                .encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)));
        payload.setNonce("0".repeat(24));
        upload.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot = new UploadDocumentRequest.SlotRequest();
        slot.setKeyId(targets.get(0).keyId());
        slot.setRecipientRef("me");
        slot.setEphemeralPub("b".repeat(64));
        slot.setWrappedDek("c".repeat(96));
        upload.setSlots(List.of(slot));
        DocumentUploadedView uploaded = documentService.upload(upload).get();

        // force a genuine DB round-trip: without this, a later findById()/findByIdForUpdate() (inside
        // fetch()/delete()) would return the SAME in-memory instance created above. Its
        // Persistable.isNew() flag is only ever cleared by @PostLoad, which fires on a real load, not
        // on persist — so within a single transaction, Spring Data's delete() would treat the entity
        // as "new" and silently skip issuing the DELETE (see SimpleJpaRepository.delete(T), which
        // no-ops when isNew()). Separate HTTP requests never hit this: each gets its own
        // EntityManager, so delete()'s findByIdForUpdate() always performs a genuine load there.
        em.flush();
        em.clear();

        // 4. org-wide listing sees it; direction filters both match (self-slot)
        assertEquals(1, documentService.list(ORG_ID, null, null, null, Pageable.unpaged()).get().total());
        assertEquals(1, documentService.list(ORG_ID, DocumentDirection.SENT, null, null, Pageable.unpaged()).get().total());
        assertEquals(1, documentService.list(ORG_ID, DocumentDirection.RECEIVED, null, null, Pageable.unpaged()).get().total());
        // filters: status + free text + pagination shape
        assertEquals(1, documentService.list(ORG_ID, null, VaultDocumentStatus.DRAFT, "statement", Pageable.unpaged()).get().total());
        assertEquals(0, documentService.list(ORG_ID, null, VaultDocumentStatus.PUBLISHED, null, Pageable.unpaged()).get().total());

        // 5. envelope fetch round-trips the ciphertext for client-side decryption
        var envelope = documentService.fetch(uploaded.documentId()).get();
        assertEquals(Base64.getEncoder().encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)),
                envelope.payload().ciphertext());
        assertEquals(1, envelope.slots().size());
        assertEquals(keyId, envelope.slots().get(0).keyId());

        // 6. creator can delete a DRAFT document
        assertTrue(documentService.delete(uploaded.documentId()).isEmpty());
        assertEquals(0, documentService.list(ORG_ID, null, null, null, Pageable.unpaged()).get().total());
    }
}
