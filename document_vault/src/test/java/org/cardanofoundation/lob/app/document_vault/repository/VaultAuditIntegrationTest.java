package org.cardanofoundation.lob.app.document_vault.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.AddressbookEntryEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultKeyEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyOrigin;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

/**
 * Envers history for the vault tables. Not {@code @Transactional}: Envers writes revisions when a
 * transaction commits, so a test that rolls back would see no history at all. Ids are random so the
 * committed rows cannot collide with other tests sharing the container.
 */
@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class VaultAuditIntegrationTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String WRAPPED_DEK = "b".repeat(96);

    @Autowired
    private VaultKeyRepository keyRepository;
    @Autowired
    private AddressbookEntryRepository entryRepository;
    @Autowired
    private VaultDocumentRepository documentRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate tx;

    @Test
    void documentLifecycleIsAuditedWithoutCiphertextOrWrappedDek() {
        String id = UUID.randomUUID().toString();

        tx.executeWithoutResult(s -> documentRepository.save(document(id)));
        tx.executeWithoutResult(s -> {
            VaultDocumentEntity doc = documentRepository.findById(id).orElseThrow();
            doc.setDescription("edited");
        });
        tx.executeWithoutResult(s -> documentRepository.delete(documentRepository.findById(id).orElseThrow()));

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> revisions = reader.getRevisions(VaultDocumentEntity.class, id);
            assertEquals(3, revisions.size());
            assertEquals(List.of(RevisionType.ADD, RevisionType.MOD, RevisionType.DEL), revisionTypes(id));

            VaultDocumentEntity added = reader.find(VaultDocumentEntity.class, id, revisions.get(0));
            assertNull(added.getDescription());
            // metadata is kept, and content_hash still pins down which ciphertext the revision had...
            assertEquals(HEX64, added.getContentHash());
            assertEquals("report.pdf", added.getFileName());
            // ...but the ciphertext itself is never copied into history
            assertNull(added.getCiphertext());

            // the recipients are kept, the wrapped key is not
            assertEquals(1, added.getSlots().size());
            DocumentSlot slot = added.getSlots().get(0);
            assertEquals("k-recipient", slot.getKeyId());
            assertEquals(HEX64, slot.getRecipientKeyHash());
            assertNull(slot.getWrappedDek());

            assertEquals("edited", reader.find(VaultDocumentEntity.class, id, revisions.get(1)).getDescription());
        });

        // the payload columns do not exist in the audit tables at all, so nothing can ever write them
        assertTrue(columns("document_vault_document_aud").stream().noneMatch("ciphertext"::equals));
        assertTrue(columns("document_vault_document_slot_aud").stream().noneMatch("wrapped_dek"::equals));
        // the live row still holds them, so the exclusion is history-only
        assertTrue(columns("document_vault_document").contains("ciphertext"));
    }

    /**
     * {@code @NotAudited} keeps {@code wrapped_dek} out of history, but it does not stop a change to it
     * from producing a revision. Hibernate compares embeddables in an element collection on every
     * field, so the {@code slots} collection is dirty and Envers records it: a MOD on the document and
     * a DEL+ADD of the slot whose audited values are identical to the previous revision.
     */
    @Test
    void changingOnlyTheWrappedDekStillCreatesARevisionWithoutRecordingTheKey() {
        String id = UUID.randomUUID().toString();
        String newWrappedDek = "c".repeat(96);

        tx.executeWithoutResult(s -> documentRepository.save(document(id)));
        tx.executeWithoutResult(s -> {
            VaultDocumentEntity doc = documentRepository.findById(id).orElseThrow();
            doc.getSlots().get(0).setWrappedDek(newWrappedDek);
        });

        // the live row did take the new value
        assertEquals(newWrappedDek, jdbcTemplate.queryForObject(
                "select wrapped_dek from document_vault_document_slot where document_id = ?", String.class, id));

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> revisions = reader.getRevisions(VaultDocumentEntity.class, id);
            assertEquals(2, revisions.size());
            assertEquals(List.of(RevisionType.ADD, RevisionType.MOD), revisionTypes(id));

            // the slot was rewritten in the second revision: removed and re-added with the same audited values
            Number second = revisions.get(1);
            List<Integer> slotRevTypes = jdbcTemplate.queryForList(
                    "select revtype from document_vault_document_slot_aud where document_id = ? and rev = ? order by revtype",
                    Integer.class, id, second.intValue());
            assertEquals(List.of(RevisionType.ADD.ordinal(), RevisionType.DEL.ordinal()), slotRevTypes);

            DocumentSlot before = reader.find(VaultDocumentEntity.class, id, revisions.get(0)).getSlots().get(0);
            DocumentSlot after = reader.find(VaultDocumentEntity.class, id, second).getSlots().get(0);
            assertEquals(before.getKeyId(), after.getKeyId());
            assertEquals(before.getRecipientRef(), after.getRecipientRef());
            assertEquals(before.getEphemeralPub(), after.getEphemeralPub());
            assertEquals(before.getRecipientKeyHash(), after.getRecipientKeyHash());
            // neither the old nor the new wrapped key reached history
            assertNull(before.getWrappedDek());
            assertNull(after.getWrappedDek());
        });
    }

    @Test
    void keyAndAddressbookEntryChangesAreAudited() {
        String keyId = UUID.randomUUID().toString();
        String entryId = UUID.randomUUID().toString();
        String org = "org-" + keyId;

        tx.executeWithoutResult(s -> {
            keyRepository.save(key(keyId, org));
            entryRepository.save(entry(entryId, org));
        });
        tx.executeWithoutResult(s -> keyRepository.findById(keyId).orElseThrow().setLabel("renamed"));
        tx.executeWithoutResult(s -> {
            keyRepository.delete(keyRepository.findById(keyId).orElseThrow());
            entryRepository.delete(entryRepository.findById(entryId).orElseThrow());
        });

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> keyRevisions = reader.getRevisions(VaultKeyEntity.class, keyId);
            assertEquals(3, keyRevisions.size());
            assertEquals("laptop", reader.find(VaultKeyEntity.class, keyId, keyRevisions.get(0)).getLabel());
            assertEquals("renamed", reader.find(VaultKeyEntity.class, keyId, keyRevisions.get(1)).getLabel());

            List<Number> entryRevisions = reader.getRevisions(AddressbookEntryEntity.class, entryId);
            assertEquals(2, entryRevisions.size());
            assertEquals("Bob Miller",
                    reader.find(AddressbookEntryEntity.class, entryId, entryRevisions.get(0)).getDisplayName());
        });
    }

    @SuppressWarnings("unchecked")
    private List<RevisionType> revisionTypes(String documentId) {
        List<Object[]> rows = AuditReaderFactory.get(em).createQuery()
                .forRevisionsOfEntity(VaultDocumentEntity.class, false, true)
                .add(AuditEntity.id().eq(documentId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList();
        return rows.stream().map(row -> (RevisionType) row[2]).toList();
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = ?", String.class, table);
    }

    private static VaultDocumentEntity document(String id) {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId(id);
        doc.setOrganisationId("org-" + id);
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
        doc.setSlots(List.of(new DocumentSlot("k-recipient", "me", HEX64, WRAPPED_DEK, HEX64)));
        return doc;
    }

    private static VaultKeyEntity key(String id, String org) {
        VaultKeyEntity key = new VaultKeyEntity();
        key.setId(id);
        key.setAccountId("acc-" + id);
        key.setOrganisationId(org);
        key.setPublicKey(HEX64);
        key.setLabel("laptop");
        key.setOrigin(KeyOrigin.SELF_ENROLLED);
        key.setAssurance(KeyAssurance.PASSKEY);
        return key;
    }

    private static AddressbookEntryEntity entry(String id, String org) {
        AddressbookEntryEntity entry = new AddressbookEntryEntity();
        entry.setId(id);
        entry.setOrganisationId(org);
        entry.setDisplayName("Bob Miller");
        entry.setPublicKey(HEX64);
        return entry;
    }
}
