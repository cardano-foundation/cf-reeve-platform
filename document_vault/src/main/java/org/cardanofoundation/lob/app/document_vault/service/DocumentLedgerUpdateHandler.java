package org.cardanofoundation.lob.app.document_vault.service;

import java.util.HashSet;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishedEvent;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentLedgerUpdateHandler {

    private final VaultDocumentRepository documentRepository;
    private final VaultKeyRepository keyRepository;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async
    @Transactional
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
            Set<String> recipientAccountIds = new HashSet<>();
            keyRepository.findAllById(keyIds).forEach(key -> recipientAccountIds.add(key.getAccountId()));
            eventPublisher.publishEvent(new DocumentPublishedEvent(
                    document.getId(), document.getOrganisationId(), recipientAccountIds));
        }
    }
}
