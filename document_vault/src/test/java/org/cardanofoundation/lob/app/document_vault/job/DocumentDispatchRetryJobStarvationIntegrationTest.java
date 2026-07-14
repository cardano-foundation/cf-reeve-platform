package org.cardanofoundation.lob.app.document_vault.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * Codex adversarial-review finding (round 3) — retry-sweep starvation regression test. Real
 * Postgres, {@code batchSize=2}, four documents stuck in {@code PUBLISHED}/{@code MARK_DISPATCH}.
 * Before the retry-cursor fix, ordering purely by {@code publishedAt} meant every sweep reselected
 * the same two oldest rows forever, and the two younger documents — whose in-memory handoff may
 * genuinely have been lost — were never retried. This proves sweep 2 picks up the rows sweep 1
 * skipped instead of reselecting sweep 1's rows again.
 *
 * <p>Deliberately NOT class-level {@code @Transactional} (same reasoning as {@code
 * DocumentLedgerUpdateHandlerRollbackIntegrationTest}): {@code DocumentDispatchRetryJob
 * #reemitStuckPublishes} is itself {@code @Transactional} and must run its own genuine,
 * independently-committing transaction on each call — a single wrapping test transaction would
 * either nest incorrectly or hide commit-visibility bugs the fix depends on. Fixture rows are
 * committed directly via {@code saveAndFlush} outside any transaction (each such call opens and
 * commits its own, exactly like production callers), and are left in place afterwards rather than
 * rolled back — the extra {@code CaptureConfig} class in {@code @ContextConfiguration} gives this
 * test its own cached Spring context (and therefore its own fresh Testcontainers Postgres instance,
 * per the shared {@code TestContainerConfig}), so there is no risk of colliding with fixture data
 * from any other document_vault integration test.
 */
@SpringBootTest
@ContextConfiguration(classes = {DocumentVaultContextIntegrationTest.TestConfig.class,
        DocumentDispatchRetryJobStarvationIntegrationTest.CaptureConfig.class})
@TestPropertySource(properties = "lob.document_vault.dispatch.batch-size=2")
@ActiveProfiles("test")
class DocumentDispatchRetryJobStarvationIntegrationTest {

    private static final String ORG_ID = "org-starvation-it";

    @TestConfiguration
    static class CaptureConfig {

        static final List<DocumentPublishCommand> CAPTURED = new CopyOnWriteArrayList<>();

        // Distinctly named (not "publishCommandCapture"): the module's shared TestConfig does a
        // broad @ComponentScan(basePackages = "org.cardanofoundation.lob") with no
        // TestTypeExcludeFilter, so every @TestConfiguration nested in any document_vault test class
        // - including this one and VaultPublishIntegrationTest.PublishTestConfig - is swept into
        // EVERY document_vault integration test's context. A same-named @Bean method here would
        // collide with that class's "publishCommandCapture" bean and fail context startup with a
        // BeanDefinitionOverrideException (see DocumentLedgerUpdateHandlerRollbackIntegrationTest's
        // class javadoc for the same pitfall in the other direction).
        @Bean
        public StarvationPublishCommandCapture starvationPublishCommandCapture() {
            return new StarvationPublishCommandCapture();
        }

        static class StarvationPublishCommandCapture {
            @EventListener
            public void on(DocumentPublishCommand command) {
                CAPTURED.add(command);
            }
        }
    }

    @Autowired
    private DocumentDispatchRetryJob job;
    @Autowired
    private VaultDocumentRepository documentRepository;

    @BeforeEach
    void setUp() {
        CaptureConfig.CAPTURED.clear();
    }

    private VaultDocumentEntity stuckDocument(String id, LocalDateTime publishedAt) {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId(id);
        doc.setOrganisationId(ORG_ID);
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        doc.setEnvelopeVersion(1);
        doc.setContentHash("a".repeat(64));
        doc.setPlaintextHash("a".repeat(64));
        doc.setCiphertext(new byte[] {1, 2, 3});
        doc.setPayloadNonce("f".repeat(24));
        doc.setSizeBytes(3L);
        doc.setCreatedByAccount("sender");
        doc.setPublishedAt(publishedAt);
        return documentRepository.saveAndFlush(doc);
    }

    private static Set<String> capturedDocumentIds() {
        return CaptureConfig.CAPTURED.stream()
                .map(DocumentPublishCommand::documentId)
                .collect(Collectors.toSet());
    }

    @Test
    void secondSweepRetriesDocumentsTheFirstSweepSkippedInsteadOfReselectingTheSameOldestRows() {
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        // batchSize (2) + 2 = 4 stuck documents, strictly increasing publishedAt
        VaultDocumentEntity doc1 = stuckDocument("doc-starve-1", base.plusMinutes(1));
        VaultDocumentEntity doc2 = stuckDocument("doc-starve-2", base.plusMinutes(2));
        VaultDocumentEntity doc3 = stuckDocument("doc-starve-3", base.plusMinutes(3));
        VaultDocumentEntity doc4 = stuckDocument("doc-starve-4", base.plusMinutes(4));

        // --- sweep 1: all four dispatchRetryAt are NULL (tied) -> tie-break falls back to
        // publishedAt ascending, so the two OLDEST documents are picked ---
        job.reemitStuckPublishes();

        assertEquals(Set.of(doc1.getId(), doc2.getId()), capturedDocumentIds(),
                "sweep 1 must retry the two oldest documents (dispatchRetryAt all NULL, tie-break by publishedAt)");

        VaultDocumentEntity reloaded1 = documentRepository.findById(doc1.getId()).orElseThrow();
        VaultDocumentEntity reloaded2 = documentRepository.findById(doc2.getId()).orElseThrow();
        VaultDocumentEntity reloaded3 = documentRepository.findById(doc3.getId()).orElseThrow();
        VaultDocumentEntity reloaded4 = documentRepository.findById(doc4.getId()).orElseThrow();
        assertNotNull(reloaded1.getDispatchRetryAt(), "attempted document must get a non-null retry cursor");
        assertNotNull(reloaded2.getDispatchRetryAt(), "attempted document must get a non-null retry cursor");
        assertNull(reloaded3.getDispatchRetryAt(), "never-attempted document must stay NULL after sweep 1");
        assertNull(reloaded4.getDispatchRetryAt(), "never-attempted document must stay NULL after sweep 1");
        // still MARK_DISPATCH: re-emitting alone never advances ledger status in production either —
        // only the (out-of-scope-here) blockchain_publisher consumer does that. The fix under test is
        // the retry cursor, which must guarantee fairness WITHOUT relying on status ever advancing.
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, reloaded1.getLedgerDispatchStatus());
        assertEquals(LedgerDispatchStatus.MARK_DISPATCH, reloaded3.getLedgerDispatchStatus());

        // --- sweep 2: doc1/doc2 now carry a non-null cursor, doc3/doc4 are still NULL. Without the
        // fix (ordering purely by publishedAt, the pre-fix behaviour) this sweep would reselect
        // doc1/doc2 again -- forever -- and doc3/doc4 would starve. ---
        CaptureConfig.CAPTURED.clear();
        job.reemitStuckPublishes();

        assertEquals(Set.of(doc3.getId(), doc4.getId()), capturedDocumentIds(),
                "sweep 2 must retry the NEXT (never-yet-attempted) documents, not reselect sweep 1's rows");

        VaultDocumentEntity reloaded3AfterSweep2 = documentRepository.findById(doc3.getId()).orElseThrow();
        VaultDocumentEntity reloaded4AfterSweep2 = documentRepository.findById(doc4.getId()).orElseThrow();
        assertNotNull(reloaded3AfterSweep2.getDispatchRetryAt());
        assertNotNull(reloaded4AfterSweep2.getDispatchRetryAt());

        // doc1/doc2's cursor from sweep 1 is untouched by sweep 2 -- they simply weren't selected
        VaultDocumentEntity reloaded1AfterSweep2 = documentRepository.findById(doc1.getId()).orElseThrow();
        VaultDocumentEntity reloaded2AfterSweep2 = documentRepository.findById(doc2.getId()).orElseThrow();
        assertEquals(reloaded1.getDispatchRetryAt(), reloaded1AfterSweep2.getDispatchRetryAt());
        assertEquals(reloaded2.getDispatchRetryAt(), reloaded2AfterSweep2.getDispatchRetryAt());
    }
}
