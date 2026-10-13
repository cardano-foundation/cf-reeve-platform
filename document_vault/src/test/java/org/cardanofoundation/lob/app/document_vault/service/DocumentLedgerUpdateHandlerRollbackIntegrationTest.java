package org.cardanofoundation.lob.app.document_vault.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.DocumentVaultContextIntegrationTest;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * real-transaction proof (the reflective pin in
 * {@code DocumentLedgerUpdateHandlerListenerPinTest} only covers "someone changed the annotation
 * back") that {@code handleLedgerUpdatedEvent} really only ever applies a ledger update AFTER the
 * publishing transaction has committed — never for one still in flight, and never for one that
 * rolls back.
 *
 * <p>Deliberately does NOT enable {@code @EnableAsync} for this context: this module's shared
 * {@code DocumentVaultContextIntegrationTest.TestConfig} does a broad
 * {@code @ComponentScan(basePackages = "org.cardanofoundation.lob")} with no
 * {@code TestTypeExcludeFilter} (that filter is only wired in automatically for
 * {@code @SpringBootApplication}-style scans), so any {@code @TestConfiguration} nested in any
 * document_vault test class — including one declaring {@code @EnableAsync} — gets swept into
 * EVERY other document_vault integration test's context too. An earlier version of this test
 * declared its own {@code @EnableAsync} + dedicated executor to make {@code @Async} genuinely
 * asynchronous; that leaked via the scan into {@code VaultPublishIntegrationTest} (which calls
 * {@code handleLedgerUpdatedEvent} directly and asserts immediately afterwards) and made it flaky.
 * Fixing the shared scan config is out of scope here, so this test relies purely on the listener's
 * own {@code @Transactional(REQUIRES_NEW)}: REQUIRES_NEW always suspends the caller's transaction
 * and opens a brand new, independent one on its own connection, regardless of which thread runs it
 * — so the safety property under test (no update visible until the publishing transaction commits)
 * holds whether or not {@code @Async} happens to be real in a given context. Assertions still poll
 * with Awaitility rather than asserting immediately, purely as a defensive measure in case
 * {@code @Async} ever does become real here.
 */
@SpringBootTest
@ContextConfiguration(classes = DocumentVaultContextIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
class DocumentLedgerUpdateHandlerRollbackIntegrationTest {

    private static final String ORG_ID = "org-rollback-it";
    private static final String TX_HASH = "tx-rollback-test";

    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private VaultDocumentRepository documentRepository;

    private VaultDocumentEntity persistStuckDocument(String id) {
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
        return documentRepository.saveAndFlush(doc);
    }

    private LedgerUpdatedEvent dispatchedEvent(String documentId) {
        return LedgerUpdatedEvent.builder()
                .organisationId(ORG_ID)
                .type(LedgerUpdateType.DOCUMENT)
                .statusUpdates(Set.of(new LedgerStatusUpdate(documentId, LedgerDispatchStatus.DISPATCHED, null,
                        Set.of(new BlockchainReceipt("CARDANO_L1", TX_HASH)))))
                .build();
    }

    @Test
    void ledgerUpdateIsDiscardedWhenThePublishingTransactionRollsBack() {
        VaultDocumentEntity doc = persistStuckDocument("doc-rollback");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(dispatchedEvent(doc.getId()));
            status.setRollbackOnly();
        });

        // AFTER_COMMIT never fires for a rolled-back transaction, so there is nothing to genuinely
        // await — poll for a short window (long enough for a background executor to have run, had
        // the listener incorrectly fired) and assert the row stayed exactly as it was.
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    VaultDocumentEntity reloaded = documentRepository.findById(doc.getId()).orElseThrow();
                    assertEquals(LedgerDispatchStatus.MARK_DISPATCH, reloaded.getLedgerDispatchStatus());
                    assertNull(reloaded.getTxHash());
                });
    }

    @Test
    void ledgerUpdateIsAppliedAfterThePublishingTransactionCommits() {
        VaultDocumentEntity doc = persistStuckDocument("doc-commit");

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(dispatchedEvent(doc.getId())));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            VaultDocumentEntity reloaded = documentRepository.findById(doc.getId()).orElseThrow();
            assertEquals(LedgerDispatchStatus.DISPATCHED, reloaded.getLedgerDispatchStatus());
            assertEquals(TX_HASH, reloaded.getTxHash());
        });
    }

    @Test
    void ledgerUpdateIsAppliedViaFallbackExecutionWhenPublishedOutsideAnyTransaction() {
        VaultDocumentEntity doc = persistStuckDocument("doc-fallback");

        // no TransactionTemplate here at all: no active transaction synchronization is present,
        // which is exactly the case fallbackExecution=true exists for (e.g. DocumentDispatchRetryJob
        // re-emitting after its own read-only transaction has already completed).
        eventPublisher.publishEvent(dispatchedEvent(doc.getId()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            VaultDocumentEntity reloaded = documentRepository.findById(doc.getId()).orElseThrow();
            assertEquals(LedgerDispatchStatus.DISPATCHED, reloaded.getLedgerDispatchStatus());
            assertEquals(TX_HASH, reloaded.getTxHash());
        });
    }
}
