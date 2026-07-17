package org.cardanofoundation.lob.app.document_vault.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.KeyRef;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishedEvent;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentLedgerUpdateHandler {

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyLookupService keyLookupService;
    private final ApplicationEventPublisher eventPublisher;

    // Codex adversarial-review finding 2 (pre-commit ledger updates, the reverse direction of the
    // publish handoff in BlockchainPublisherEventHandler#handleDocumentPublishCommand):
    // - AFTER_COMMIT, not the default BEFORE_COMMIT-ish immediate dispatch of @EventListener: this
    //   handler persists DISPATCHED/FAILED/FINALIZED status, txHash and ipfsCid onto the vault
    //   document. LedgerUpdatedEvent is published from inside blockchain_publisher's own transactional
    //   dispatch flow (see LedgerUpdatedEventPublisher#send), so without AFTER_COMMIT the vault could
    //   persist phantom ledger state read from a publisher transaction that later rolls back.
    // - fallbackExecution = true is REQUIRED (not incidental): some emitters — including this module's
    //   own tests — invoke the handler or publish this event with no active transaction synchronization
    //   present. AFTER_COMMIT listeners are silently skipped in that case unless fallbackExecution is set.
    // - propagation = REQUIRES_NEW: Spring refuses to start a @TransactionalEventListener method that
    //   also carries a plain (REQUIRED) @Transactional — "must not be annotated with @Transactional
    //   unless declared as REQUIRES_NEW or NOT_SUPPORTED" (fails fast at context startup). REQUIRES_NEW
    //   is also the semantically correct choice regardless: this method runs on the @Async executor
    //   thread, decoupled from the publishing transaction, so it always needs its own fresh transaction
    //   rather than trying to join one.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleLedgerUpdatedEvent(LedgerUpdatedEvent event) {
        if (event.getType() != LedgerUpdateType.DOCUMENT) {
            return;
        }
        log.info("Received document ledger update for organisation:{}, updates:{}",
                event.getOrganisationId(), event.getStatusUpdates().size());

        for (LedgerStatusUpdate update : event.getStatusUpdates()) {
            documentRepository.findById(update.getId()).ifPresentOrElse(
                    document -> apply(document, update),
                    () -> log.debug("Ignoring ledger update for unknown document: {}", update.getId()));
        }
    }

    private void apply(VaultDocumentEntity document, LedgerStatusUpdate update) {
        // capture BEFORE overwriting: DocumentPublishedEvent must fire exactly once, on the FIRST finality
        boolean firstFinality = update.getStatus() == LedgerDispatchStatus.FINALIZED
                && document.getLedgerDispatchStatus() != LedgerDispatchStatus.FINALIZED;

        document.setLedgerDispatchStatus(update.getStatus());
        document.setLedgerDispatchError(update.getLedgerDispatchStatusErrorReason());
        for (BlockchainReceipt receipt : update.getBlockchainReceipts()) {
            if ("IPFS".equals(receipt.getType())) {
                document.setIpfsCid(receipt.getHash());
            } else if (receipt.getHash() != null) {
                document.setTxHash(receipt.getHash());
            }
        }
        documentRepository.save(document);

        if (firstFinality) {
            Set<String> keyIds = new HashSet<>();
            document.getSlots().forEach(slot -> keyIds.add(slot.getKeyId()));
            // Addressbook contacts resolve to a null account and are filtered out: the event carries
            // Reeve account ids for in-app notification, and a contact has no login to notify. They see
            // the document as a published record in the Indexer, which is how they were always meant to.
            Set<String> recipientAccountIds = keyLookupService.findAllById(keyIds).values().stream()
                    .map(KeyRef::accountId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            eventPublisher.publishEvent(new DocumentPublishedEvent(
                    document.getId(), document.getOrganisationId(), recipientAccountIds));
        }
    }
}
