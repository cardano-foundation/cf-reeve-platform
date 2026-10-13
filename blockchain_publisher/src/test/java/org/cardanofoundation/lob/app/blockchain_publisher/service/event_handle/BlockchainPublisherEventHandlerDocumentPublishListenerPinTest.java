package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;

/**
 * why reflection, not a live two-transaction race — a true
 * integration test for "does the listener really wait for commit" would need a real transactional
 * caller and is out of proportion here. What CAN regress silently is someone "simplifying"
 * {@code handleDocumentPublishCommand} back to a plain {@code @EventListener}, which would quietly
 * reopen both failure modes it closes: (a) the listener acting on a vault-side
 * {@code DocumentPublishCommand} before the publish transaction commits (orphan publisher row on
 * rollback), and (b) — if {@code fallbackExecution} were dropped instead — silently swallowing the
 * recovery job's re-emissions, since {@code DocumentDispatchRetryJob} may publish this command
 * outside of any active transaction synchronization. This pins the annotation the same way
 * {@code VaultDocumentRepositoryLockTest} pins the {@code @Lock} on {@code findByIdForUpdate}.
 */
class BlockchainPublisherEventHandlerDocumentPublishListenerPinTest {

    @Test
    void handleDocumentPublishCommandIsAfterCommitFallbackAndAsync() throws NoSuchMethodException {
        Method method = BlockchainPublisherEventHandler.class
                .getMethod("handleDocumentPublishCommand", DocumentPublishCommand.class);

        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(listener, "handleDocumentPublishCommand must stay a @TransactionalEventListener: "
                + "an irreversible on-chain action must never act on a vault-side command whose "
                + "transaction could still roll back.");
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase(),
                "must fire AFTER_COMMIT, not on the default phase, or a vault rollback could leave an "
                        + "orphan publisher row for a document the vault never actually published.");
        assertTrue(listener.fallbackExecution(),
                "fallbackExecution must stay true: DocumentDispatchRetryJob re-emits this same command "
                        + "and may do so with no active transaction synchronization present — without "
                        + "fallbackExecution, an AFTER_COMMIT listener silently drops such events.");

        assertNotNull(method.getAnnotation(Async.class),
                "must stay @Async so storing the publisher row never blocks the caller.");
    }
}
