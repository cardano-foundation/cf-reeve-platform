package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.hibernate.Hibernate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.request.RegisterKeyRequest;
import org.cardanofoundation.lob.app.document_vault.domain.request.UploadDocumentRequest;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationRepository;

/**
 * {@code VaultDocumentService
 * #loadForAttestation} is the read {@code blockchain_publisher}'s {@code
 * DocumentAttestationTargetProvider} uses to build the freeze envelope — but that mapping ({@code
 * VaultDocumentService#toPublishCommand}) runs in the CALLER, well after {@code
 * loadForAttestation}'s own {@code @Transactional(readOnly = true)} boundary has committed and
 * closed. {@code VaultDocumentEntity#getSlots()} is a {@code LAZY @ElementCollection}; without
 * eagerly initializing it inside {@code loadForAttestation} itself, this only works by accident of
 * Spring Boot's {@code open-in-view} default keeping a session open for the life of an HTTP request
 * — a cross-module method call from {@code blockchain_publisher} is not a request.
 *
 * <p>This test proves the real failure mode directly: {@link TestTransaction#end()} without a
 * following {@link TestTransaction#start()} closes the persistence context for real (no ambient
 * transaction, no request, no open-in-view filter in play — this is a plain method call), exactly
 * like the cross-module scenario. Without the eager-initialization fix, reading {@code
 * loaded.getSlots()} below would throw {@code LazyInitializationException} instead of returning
 * data.
 *
 * <p>Deliberately its OWN test class/organisation id (not folded into {@link
 * VaultPublishIntegrationTest} or {@link VaultDocumentFlowIntegrationTest}): the real, permanent
 * {@link TestTransaction#flagForCommit()} this test performs must never share a test-class-wide
 * {@code @BeforeEach}-inserted organisation row with a sibling test method — a second real commit of
 * the SAME row (by a second test method reusing that fixture) would race the first on the
 * organisation's primary key, order-dependently, exactly the failure mode this isolation avoids.
 */
@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class VaultDocumentServiceLoadForAttestationIntegrationTest {

    private static final String ORG_ID = "org-attest-lazy-check";

    @Autowired
    private VaultKeyService keyService;
    @Autowired
    private VaultDocumentService documentService;
    @Autowired
    private OrganisationRepository organisationRepository;

    @BeforeEach
    void organisation() {
        organisationRepository.saveAndFlush(Organisation.builder()
                .id(ORG_ID)
                .name("Attest Lazy Check Org")
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
    void loadForAttestationReturnsSlotsInitializedOutsideAnyPersistenceContext() {
        RegisterKeyRequest keyRequest = new RegisterKeyRequest();
        keyRequest.setOrganisationId(ORG_ID);
        keyRequest.setLabel("attest-laptop");
        keyRequest.setPublicKey("f".repeat(64));
        String keyId = keyService.registerKey(keyRequest).get().keyId();

        UploadDocumentRequest upload = new UploadDocumentRequest();
        upload.setOrganisationId(ORG_ID);
        upload.setEnvelopeVersion(1);
        upload.setFileName("attest-lazy-check.pdf");
        upload.setPlaintextHash("1".repeat(64));
        UploadDocumentRequest.PayloadRequest payload = new UploadDocumentRequest.PayloadRequest();
        payload.setCiphertext(Base64.getEncoder()
                .encodeToString("attest-ciphertext".getBytes(StandardCharsets.UTF_8)));
        payload.setNonce("1".repeat(24));
        upload.setPayload(payload);
        UploadDocumentRequest.SlotRequest slot = new UploadDocumentRequest.SlotRequest();
        slot.setKeyId(keyId);
        slot.setRecipientRef("attest-recipient");
        slot.setEphemeralPub("2".repeat(64));
        slot.setWrappedDek("3".repeat(96));
        upload.setSlots(List.of(slot));
        String documentId = documentService.upload(upload).get().documentId();

        // Close the persistence context for real (see class javadoc) before calling the method
        // under test, so it opens and commits its OWN, separate transaction — exactly matching
        // blockchain_publisher's cross-module call.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        Either<ProblemDetail, VaultDocumentEntity> result = documentService.loadForAttestation(documentId, "sender");
        assertTrue(result.isRight());
        VaultDocumentEntity loaded = result.get();

        assertTrue(Hibernate.isInitialized(loaded.getSlots()),
                "loadForAttestation must return an entity whose slots are already initialized: "
                        + "callers map it well outside this method's own transaction/session");
        assertEquals(1, loaded.getSlots().size());
        assertEquals("attest-recipient", loaded.getSlots().get(0).getRecipientRef());

        // Restart a transaction so the test-framework teardown has something to roll back (matches
        // the same TestTransaction idiom used elsewhere, e.g. VaultPublishIntegrationTest).
        TestTransaction.start();
    }
}
