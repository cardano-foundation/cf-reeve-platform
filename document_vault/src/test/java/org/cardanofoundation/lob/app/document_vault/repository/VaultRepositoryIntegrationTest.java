package org.cardanofoundation.lob.app.document_vault.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
class VaultRepositoryIntegrationTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String HEX96 = "b".repeat(96);

    @Autowired
    private VaultKeyRepository keyRepository;
    @Autowired
    private WrappedRecordRepository recordRepository;
    @Autowired
    private VaultDocumentRepository documentRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private VaultKeyEntity key(String id, String accountId, String publicKey, String org) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId(accountId);
        key.setOrganisationId(org);
        key.setAccountName("Name " + accountId);
        key.setEmail(accountId + "@example.org");
        key.setPublicKey(publicKey);
        key.setLabel("laptop");
        // origin/assurance are NOT NULL columns — a fixture without them fails on flush, not on assert
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    @Test
    void keyRoundTripAndOrgQueryScopesToOrganisation() {
        keyRepository.save(key("k1", "acc1", HEX64, "org1"));
        keyRepository.save(key("k3", "acc2", "d".repeat(64), "org2"));
        // same public key, same account, DIFFERENT org — allowed by design (one entry per org)
        keyRepository.save(key("k4", "acc1", HEX64, "org2"));

        List<VaultKeyEntity> org1Keys = keyRepository.findByOrganisationId("org1");
        assertEquals(1, org1Keys.size());
        assertEquals("k1", org1Keys.get(0).getId());
        assertEquals("acc1@example.org", org1Keys.get(0).getEmail());
        assertEquals("org1", org1Keys.get(0).getOrganisationId());
        assertEquals(KeyOrigin.SELF_ENROLLED, org1Keys.get(0).getOrigin());
        assertEquals(KeyAssurance.PASSKEY, org1Keys.get(0).getAssurance());

        assertEquals(1, keyRepository.findByAccountIdInAndOrganisationId(List.of("acc1", "acc2"), "org1").size());
        assertTrue(keyRepository.existsByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64));
        assertTrue(keyRepository.findByAccountIdAndOrganisationIdAndPublicKey("acc1", "org1", HEX64).isPresent());
    }

    @Test
    void wrappedRecordRoundTripsByteIdentical() {
        String blob = "{\"v\":1,\"wrappedPriv\":\"00ff\",\"unicode\":\"snowman \\u2603 emoji 🎉\"}";
        WrappedRecordEntity record = new WrappedRecordEntity();
        record.setId(new WrappedRecordId("acc1", "cred-1"));
        record.setRecord(blob);
        record.setVersion(1);
        recordRepository.save(record);

        // force a genuine DB round-trip: without this, findById below would return the
        // same in-memory instance from the first-level cache instead of hitting PostgreSQL
        em.flush();
        em.clear();

        WrappedRecordEntity reloaded = recordRepository.findById(new WrappedRecordId("acc1", "cred-1")).orElseThrow();
        assertEquals(blob, reloaded.getRecord());
        assertArrayEquals(blob.getBytes(StandardCharsets.UTF_8), reloaded.getRecord().getBytes(StandardCharsets.UTF_8));
        assertEquals(1, recordRepository.findByIdAccountId("acc1", Pageable.unpaged()).getTotalElements());
    }

    @Test
    void documentRoundTripAndSentReceivedQueries() {
        keyRepository.save(key("k1", "sender", HEX64, "org1"));
        keyRepository.save(key("k2", "recipient", "e".repeat(64), "org1"));

        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setStatus(VaultDocumentStatus.DRAFT);
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        doc.setEnvelopeVersion(1);
        doc.setContentHash(HEX64);
        doc.setPlaintextHash(HEX64);
        doc.setCiphertext(new byte[] {1, 2, 3});
        doc.setPayloadNonce("f".repeat(24));
        doc.setFileName("report.pdf");
        doc.setSizeBytes(3L);
        doc.setCreatedByAccount("sender");
        doc.setCreatedByName("Sender Name");
        doc.setSlots(List.of(
                new DocumentSlot("k1", "me", HEX64, HEX96),
                new DocumentSlot("k2", "recipient label", HEX64, HEX96)));
        documentRepository.save(doc);

        // second document, authored by "recipient", shared with "sender" (slot -> k1)
        VaultDocumentEntity doc2 = new VaultDocumentEntity();
        doc2.setId("doc2");
        doc2.setOrganisationId("org1");
        doc2.setEnvelopeVersion(1);
        doc2.setContentHash(HEX64);
        doc2.setPlaintextHash(HEX64);
        doc2.setCiphertext(new byte[] {4, 5, 6});
        doc2.setPayloadNonce("f".repeat(24));
        doc2.setFileName("invoice.pdf");
        doc2.setSizeBytes(3L);
        doc2.setCreatedByAccount("recipient");
        doc2.setCreatedByName("Recipient Name");
        doc2.setSlots(List.of(new DocumentSlot("k1", "back at you", HEX64, HEX96)));
        documentRepository.save(doc2);

        // force a genuine DB round-trip: without this, findById below would return the
        // same in-memory instance from the first-level cache instead of hitting PostgreSQL
        em.flush();
        em.clear();

        VaultDocumentEntity reloaded = documentRepository.findById("doc1").orElseThrow();
        assertArrayEquals(new byte[] {1, 2, 3}, reloaded.getCiphertext());
        assertEquals(2, reloaded.getSlots().size());
        assertEquals("k2", reloaded.getSlots().get(1).getKeyId());
        assertEquals(VaultDocumentStatus.DRAFT, reloaded.getStatus());
        assertEquals(LedgerDispatchStatus.NOT_DISPATCHED, reloaded.getLedgerDispatchStatus());

        // org-wide, unfiltered
        assertEquals(2, documentRepository.search("org1", "anyone", null, null, null, Pageable.unpaged()).getTotalElements());
        // REAL multi-page scenario exercising the explicit countQuery: 2 rows, page size 1 -> 2 pages
        Page<VaultDocumentEntity> firstPage = documentRepository.search("org1", "anyone", null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 1,
                        org.springframework.data.domain.Sort.by("createdAt").descending()));
        assertEquals(2, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(1, firstPage.getContent().size());
        // direction filters (relative to the caller)
        assertEquals(1, documentRepository.search("org1", "sender", "SENT", null, null, Pageable.unpaged()).getTotalElements());
        assertEquals(1, documentRepository.search("org1", "recipient", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        // sender's key k1 sits in BOTH docs' slots (doc1 self-slot + doc2 shared-back) -> 2;
        // self-slots deliberately count as RECEIVED (sender self-access, blueprint §5)
        assertEquals(2, documentRepository.search("org1", "sender", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        assertEquals(0, documentRepository.search("org1", "stranger", "RECEIVED", null, null, Pageable.unpaged()).getTotalElements());
        // status + q filters
        assertEquals(1, documentRepository.search("org1", "anyone", null, VaultDocumentStatus.DRAFT, "report", Pageable.unpaged()).getTotalElements());
        assertEquals(0, documentRepository.search("org1", "anyone", null, VaultDocumentStatus.PUBLISHED, null, Pageable.unpaged()).getTotalElements());
    }

    /**
     * Controller-mandated fix (Task 3 review): pins the JPQL {@code escape '\'} contract at the
     * real-database level. {@code q} arrives here PRE-ESCAPED (the service layer's job) — this test
     * simulates that by passing an already-escaped literal directly to the repository.
     */
    @Test
    void searchEscapesLikeMetacharactersInFreeTextQuery() {
        keyRepository.save(key("k1", "sender", HEX64, "org1"));

        VaultDocumentEntity literalDoc = new VaultDocumentEntity();
        literalDoc.setId("doc-literal");
        literalDoc.setOrganisationId("org1");
        literalDoc.setEnvelopeVersion(1);
        literalDoc.setContentHash(HEX64);
        literalDoc.setPlaintextHash(HEX64);
        literalDoc.setCiphertext(new byte[] {1});
        literalDoc.setPayloadNonce("f".repeat(24));
        literalDoc.setFileName("100%_off.pdf"); // literal percent AND underscore in the filename
        literalDoc.setSizeBytes(1L);
        literalDoc.setCreatedByAccount("sender");
        literalDoc.setSlots(List.of(new DocumentSlot("k1", "me", HEX64, HEX96)));
        documentRepository.save(literalDoc);

        VaultDocumentEntity plainDoc = new VaultDocumentEntity();
        plainDoc.setId("doc-plain");
        plainDoc.setOrganisationId("org1");
        plainDoc.setEnvelopeVersion(1);
        plainDoc.setContentHash(HEX64);
        plainDoc.setPlaintextHash(HEX64);
        plainDoc.setCiphertext(new byte[] {2});
        plainDoc.setPayloadNonce("f".repeat(24));
        plainDoc.setFileName("plain.pdf"); // no LIKE metacharacters at all
        plainDoc.setSizeBytes(1L);
        plainDoc.setCreatedByAccount("sender");
        plainDoc.setSlots(List.of(new DocumentSlot("k1", "me", HEX64, HEX96)));
        documentRepository.save(plainDoc);

        em.flush();
        em.clear();

        // an escaped literal substring ("100%_off", metacharacters backslash-escaped) finds
        // exactly the one document that actually contains it — not a false-positive, not a miss
        assertEquals(1, documentRepository.search("org1", "anyone", null, null, "100\\%\\_off", Pageable.unpaged())
                .getTotalElements());
        // an escaped "%" means "a literal percent sign", NOT "match anything" — it must find only
        // the document that has one, never every row in the organisation
        assertEquals(1, documentRepository.search("org1", "anyone", null, null, "\\%", Pageable.unpaged())
                .getTotalElements());
        // same for an escaped "_": literal underscore, not the single-character wildcard
        assertEquals(1, documentRepository.search("org1", "anyone", null, null, "\\_", Pageable.unpaged())
                .getTotalElements());
    }

    private VaultDocumentEntity minimalDocument(String id, VaultDocumentStatus status) {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId(id);
        doc.setOrganisationId("org1");
        doc.setStatus(status);
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        doc.setEnvelopeVersion(1);
        doc.setContentHash(HEX64);
        doc.setPlaintextHash(HEX64);
        doc.setCiphertext(new byte[] {1, 2, 3});
        doc.setPayloadNonce("f".repeat(24));
        doc.setSizeBytes(3L);
        doc.setCreatedByAccount("sender");
        return doc;
    }

    /**
     * Pins the retention job's published lock (settled product decision) at the real
     * repository/DB level: {@code deleteByStatusAndCreatedAtBefore} must purge stale DRAFT
     * envelopes while a PUBLISHED envelope of the same age is left untouched, no matter how old.
     */
    @Test
    void deleteByStatusAndCreatedAtBeforePurgesOnlyStaleDraftDocuments() {
        documentRepository.save(minimalDocument("doc-old-draft", VaultDocumentStatus.DRAFT));
        documentRepository.save(minimalDocument("doc-old-published", VaultDocumentStatus.PUBLISHED));
        documentRepository.save(minimalDocument("doc-recent-draft", VaultDocumentStatus.DRAFT));

        // flush so the raw JDBC backdate below (and the derived delete query's own SELECT)
        // see committed rows rather than pending first-level-cache state
        em.flush();
        LocalDateTime old = LocalDateTime.now().minusDays(40);
        jdbcTemplate.update("update document_vault_document set created_at = ? where document_id in (?, ?)",
                old, "doc-old-draft", "doc-old-published");
        // established pattern (Task 8 review): entities created & purged within one @Transactional
        // method keep Persistable.isNew() true until @PostLoad fires on a fresh load — clear the
        // first-level cache so the delete query reloads rows rather than acting on stale state.
        em.clear();

        long deleted = documentRepository.deleteByStatusAndCreatedAtBefore(
                VaultDocumentStatus.DRAFT, LocalDateTime.now().minusDays(30));

        assertEquals(1, deleted);
        assertTrue(documentRepository.findById("doc-old-draft").isEmpty());
        // published lock extends to retention: never purged, no matter the age
        assertTrue(documentRepository.findById("doc-old-published").isPresent());
        assertTrue(documentRepository.findById("doc-recent-draft").isPresent());
    }
}
