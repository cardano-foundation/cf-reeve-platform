package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.enums.DocumentDirection;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.request.CreateAddressbookEntryRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.DocumentView;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

@SpringBootTest
@ContextConfiguration(classes = {DocumentVaultContextIntegrationTest.TestConfig.class,
        VaultPublishIntegrationTest.PublishTestConfig.class})
@ActiveProfiles("test")
@Transactional
class VaultPublishIntegrationTest {

    private static final String ORG_ID = "org-publish";
    private static final String CANARY_EMAIL = "canary-mail@example.org";

    @TestConfiguration
    static class PublishTestConfig {

        static final List<DocumentPublishCommand> CAPTURED = new CopyOnWriteArrayList<>();

        @Bean
        public IpfsAvailability testIpfsAvailability() {
            return () -> true;
        }

        @Bean
        public PublishCommandCapture publishCommandCapture() {
            return new PublishCommandCapture();
        }

        static class PublishCommandCapture {
            @EventListener
            public void on(DocumentPublishCommand command) {
                CAPTURED.add(command);
            }
        }
    }

    @Autowired
    private VaultKeyService keyService;
    @Autowired
    private AddressbookService addressbookService;
    @Autowired
    private RecipientResolutionService resolutionService;
    @Autowired
    private VaultDocumentService documentService;
    @Autowired
    private DocumentLedgerUpdateHandler ledgerUpdateHandler;
    @Autowired
    private OrganisationRepository organisationRepository;

    @BeforeEach
    void setUp() {
        PublishTestConfig.CAPTURED.clear();
        organisationRepository.saveAndFlush(Organisation.builder()
                .id(ORG_ID)
                .name("Publish Org")
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
    void publishFlowLocksDocumentAndCommandCarriesNoPii() {
        RegisterKeyRequest keyRequest = new RegisterKeyRequest();
        keyRequest.setOrganisationId(ORG_ID);
        keyRequest.setLabel("laptop");
        keyRequest.setPublicKey("a".repeat(64));
        keyService.registerKey(keyRequest);

        // The canary rides on an ADDRESSBOOK contact, which is where e-mail lives now that an
        // organisation key takes its identity from Keycloak instead. Wrapping the document to a contact
        // therefore exercises the path where PII could actually leak into a publish command.
        CreateAddressbookEntryRequest contact = new CreateAddressbookEntryRequest();
        contact.setOrganisationId(ORG_ID);
        contact.setDisplayName("Canary Contact");
        contact.setEmail(CANARY_EMAIL);
        contact.setPublicKey("d".repeat(64));
        String keyId = addressbookService.create(contact).get().entryId();

        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setOrganisationId(ORG_ID);
        upload.setEnvelopeVersion(1);
        upload.setFileName("very-secret-filename.pdf");
        upload.setPlaintextHash("0".repeat(64));
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder()
                .encodeToString("ciphertext-bytes".getBytes(StandardCharsets.UTF_8)));
        payload.setNonce("0".repeat(24));
        upload.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot = new UploadDocumentRequest.SlotRequest();
        slot.setKeyId(keyId);
        slot.setRecipientRef("canary-recipient-label");
        slot.setEphemeralPub("b".repeat(64));
        slot.setWrappedDek("c".repeat(96));
        upload.setSlots(List.of(slot));
        String documentId = documentService.upload(upload).get().documentId();

        // publish
        DocumentView published = documentService.publish(documentId).get();
        assertEquals(VaultDocumentStatus.PUBLISHED, published.status());
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, published.ledgerDispatchStatus());

        // the command that will feed IPFS/L1 carries no PII
        assertEquals(1, PublishTestConfig.CAPTURED.size());
        String serialisedCommand = PublishTestConfig.CAPTURED.get(0).toString();
        assertFalse(serialisedCommand.contains(CANARY_EMAIL));
        assertFalse(serialisedCommand.contains("very-secret-filename"));
        assertFalse(serialisedCommand.contains("canary-recipient-label"));
        assertFalse(serialisedCommand.contains(keyId));

        // republish rejected, delete locked
        assertTrue(documentService.publish(documentId).isLeft());
        assertTrue(documentService.delete(documentId).isPresent());

        // commit for real before simulating the publisher's status-back: handleLedgerUpdatedEvent
        // now runs in its own REQUIRES_NEW transaction (Codex adversarial-review finding 2 of round
        // 2 — it must never join/see an in-flight caller transaction), which means it opens a
        // genuinely separate DB connection. That connection can only see the document row above if
        // it has actually been committed — exactly as in production, where the vault's publish()
        // transaction has long since committed by the time blockchain_publisher's ledger update
        // ever arrives. Without this commit, the update below would silently no-op (findById on the
        // separate connection would see nothing yet).
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        // simulate the publisher's status-back (handler called synchronously)
        ledgerUpdateHandler.handleLedgerUpdatedEvent(LedgerUpdatedEvent.builder()
                .organisationId(ORG_ID)
                .type(LedgerUpdateType.DOCUMENT)
                .statusUpdates(Set.of(new LedgerStatusUpdate(documentId, LedgerDispatchStatus.FINALIZED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", "tx-1"),
                                new BlockchainReceipt("IPFS", "bafy-1")))))
                .build());

        DocumentView finalized = documentService.list(ORG_ID, DocumentDirection.SENT, null, null, Pageable.unpaged())
                .get().content().get(0);
        assertEquals(LedgerDispatchStatus.FINALIZED, finalized.ledgerDispatchStatus());
        assertEquals("tx-1", finalized.txHash());
        assertEquals("bafy-1", finalized.ipfsCid());
    }
}
