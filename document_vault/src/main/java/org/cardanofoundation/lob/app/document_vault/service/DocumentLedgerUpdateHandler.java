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

    // AFTER_COMMIT: LedgerUpdatedEvent is published from inside blockchain_publisher's dispatch
    // transaction, so acting earlier could persist ledger state onto the vault document that the
    // publisher then rolls back.
    //
    // fallbackExecution is required, not incidental: some emitters publish this event with no active
    // transaction synchronization, and AFTER_COMMIT listeners are silently skipped in that case.
    //
    // REQUIRES_NEW because Spring rejects a @TransactionalEventListener carrying a plain REQUIRED
    // @Transactional, and because this runs on the async executor, detached from any caller's
    // transaction.
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
        // Captured before the overwrite below: DocumentPublishedEvent fires once, on first finality.
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
            // Addressbook contacts resolve to a null account and drop out: the event carries Reeve
            // account ids for in-app notification, and a contact has no login to notify.
            Set<String> recipientAccountIds = keyLookupService.findAllById(keyIds).values().stream()
                    .map(KeyRef::accountId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            eventPublisher.publishEvent(new DocumentPublishedEvent(
                    document.getId(), document.getOrganisationId(), recipientAccountIds));
        }
    }
}
