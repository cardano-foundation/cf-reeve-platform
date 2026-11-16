package org.cardanofoundation.lob.app.keri_attestation.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
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

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;

/**
 * Envers history for ceremonies and identity links. Not {@code @Transactional}: Envers writes revisions
 * when a transaction commits, so a test that rolls back would see no history at all. Ids are random so
 * the committed rows cannot collide with other tests sharing the container.
 */
@SpringBootTest
@ContextConfiguration(classes = CeremonyRepositoryTest.TestConfig.class)
@ActiveProfiles("test")
class KeriAuditIntegrationTest {

    @Autowired
    private KeriAttestationCeremonyRepository ceremonyRepository;
    @Autowired
    private KeriIdentityLinkRepository identityLinkRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate tx;

    /** Who attested is recorded at the step it happened, and survives later steps overwriting the row. */
    @Test
    void everyCeremonyStepIsAuditedWithWhoAttested() {
        String id = UUID.randomUUID().toString();

        tx.executeWithoutResult(s -> ceremonyRepository.save(ceremony(id)));
        tx.executeWithoutResult(s -> {
            KeriAttestationCeremonyEntity c = ceremonyRepository.findById(id).orElseThrow();
            c.setState(CeremonyState.ATTEST_ANCHORED);
            c.setAttesterAid("Ewallet-aid");
            c.setKelSequence("3");
            c.setKelEventSaid("Eevent");
        });
        tx.executeWithoutResult(s -> ceremonyRepository.findById(id).orElseThrow()
                .setState(CeremonyState.CONSUMED));

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> revisions = reader.getRevisions(KeriAttestationCeremonyEntity.class, id);
            assertEquals(3, revisions.size());

            KeriAttestationCeremonyEntity created = reader.find(KeriAttestationCeremonyEntity.class, id, revisions.get(0));
            assertEquals(CeremonyState.CREATED, created.getState());
            assertEquals("user-" + id, created.getUserId());
            assertNull(created.getAttesterAid());

            KeriAttestationCeremonyEntity anchored = reader.find(KeriAttestationCeremonyEntity.class, id, revisions.get(1));
            assertEquals(CeremonyState.ATTEST_ANCHORED, anchored.getState());
            assertEquals("Ewallet-aid", anchored.getAttesterAid());
            assertEquals("Eevent", anchored.getKelEventSaid());

            assertEquals(CeremonyState.CONSUMED,
                    reader.find(KeriAttestationCeremonyEntity.class, id, revisions.get(2)).getState());
        });

        // the dead columns are not audited, so they have no column in history
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'keri_attestation_ceremony_aud'",
                String.class);
        assertTrue(columns.contains("attester_aid"));
        assertTrue(columns.stream().noneMatch(List.of("agree_atc", "step_phase", "auth_begin_tx_hash")::contains));
    }

    /** A relink replaces the live AID; history keeps the wallet the user was linked to before. */
    @Test
    void relinkKeepsThePreviousAidInHistory() {
        String userId = "user-" + UUID.randomUUID();

        tx.executeWithoutResult(s -> {
            KeriIdentityLinkEntity link = new KeriIdentityLinkEntity();
            link.setUserId(userId);
            link.setBindingVersion(1);
            link.setAid("Eold-aid");
            identityLinkRepository.save(link);
        });
        tx.executeWithoutResult(s -> {
            KeriIdentityLinkEntity link = identityLinkRepository.findById(userId).orElseThrow();
            link.setBindingVersion(2);
            link.setAid("Enew-aid");
        });

        tx.executeWithoutResult(s -> {
            AuditReader reader = AuditReaderFactory.get(em);
            List<Number> revisions = reader.getRevisions(KeriIdentityLinkEntity.class, userId);
            assertEquals(2, revisions.size());
            assertEquals("Eold-aid", reader.find(KeriIdentityLinkEntity.class, userId, revisions.get(0)).getAid());
            assertEquals("Enew-aid", reader.find(KeriIdentityLinkEntity.class, userId, revisions.get(1)).getAid());
        });
    }

    private static KeriAttestationCeremonyEntity ceremony(String id) {
        KeriAttestationCeremonyEntity ceremony = new KeriAttestationCeremonyEntity();
        ceremony.setId(id);
        ceremony.setUserId("user-" + id);
        ceremony.setBindingVersion(1);
        ceremony.setTargetType("DOCUMENT");
        ceremony.setTargetId("doc-" + id);
        ceremony.setState(CeremonyState.CREATED);
        ceremony.setAttemptGeneration(0);
        ceremony.setExpiresAt(LocalDateTime.now().plusHours(1));
        return ceremony;
    }
}
