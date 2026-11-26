package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;

/**
 * the reverse direction of the durable publish
 * handoff pinned by {@code BlockchainPublisherEventHandlerDocumentPublishListenerPinTest}: why
 * reflection, not a live two-transaction race — a true integration test for "does the listener
 * really wait for commit" would need a real transactional caller (see
 * {@code DocumentLedgerUpdateHandlerRollbackIntegrationTest} for that). What CAN regress silently
 * is someone "simplifying" {@code handleLedgerUpdatedEvent} back to a plain {@code @EventListener},
 * which would quietly reopen the failure mode it closes: persisting DISPATCHED/FAILED/FINALIZED
 * status, txHash and ipfsCid read from a blockchain_publisher transaction that later rolls back —
 * phantom ledger state on the vault document. Dropping {@code fallbackExecution} instead would
 * silently swallow updates published with no active transaction synchronization present. This pins
 * the annotations the same way {@code VaultDocumentRepositoryLockTest} pins the {@code @Lock} on
 * {@code findByIdForUpdate}.
 */
class DocumentLedgerUpdateHandlerListenerPinTest {

    @Test
    void handleLedgerUpdatedEventIsAfterCommitFallbackAsyncAndTransactional() throws NoSuchMethodException {
        Method method = DocumentLedgerUpdateHandler.class
                .getMethod("handleLedgerUpdatedEvent", LedgerUpdatedEvent.class);

        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(listener, "handleLedgerUpdatedEvent must stay a @TransactionalEventListener: "
                + "the vault must never persist ledger status read from a publisher transaction that "
                + "could still roll back.");
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase(),
                "must fire AFTER_COMMIT, not on the default phase, or the vault could persist phantom "
                        + "DISPATCHED/FAILED/FINALIZED status, txHash or ipfsCid from a publisher "
                        + "transaction that later rolls back.");
        assertTrue(listener.fallbackExecution(),
                "fallbackExecution must stay true: some emitters publish LedgerUpdatedEvent with no "
                        + "active transaction synchronization present — without fallbackExecution, an "
                        + "AFTER_COMMIT listener silently drops such events.");

        assertNotNull(method.getAnnotation(Async.class),
                "must stay @Async so applying the ledger update never blocks the publishing caller.");

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional,
                "must stay @Transactional for its own DB work (document save + published-event lookup).");
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                "must stay REQUIRES_NEW: Spring refuses to start a @TransactionalEventListener method "
                        + "that also carries a plain (REQUIRED) @Transactional, failing fast at context "
                        + "startup; REQUIRES_NEW is also correct on its own merits since this method runs "
                        + "on the @Async executor thread, decoupled from the publishing transaction.");
    }
}
