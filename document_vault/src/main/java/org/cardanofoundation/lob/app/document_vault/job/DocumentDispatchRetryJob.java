package org.cardanofoundation.lob.app.document_vault.job;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.service.VaultDocumentService;

/**
 * Recovery sweep for the publish handoff. {@link VaultDocumentService#publish(String)} commits the
 * vault row to {@code PUBLISHED}/{@code MARK_DISPATCH} and then fires an in-memory
 * {@code DocumentPublishCommand}. If the process crashes, or the async executor rejects the task,
 * between that commit and the publisher storing its row, the document is stuck: {@code PUBLISHED} is
 * a permanent lock, yet no dispatch record exists and nothing would retry the handoff. This job
 * periodically re-emits the command for documents still resting in that state.
 *
 * <p>Re-emitting is harmless when nothing went wrong — the first command may simply still be in
 * flight — because {@code DocumentEntityRepositoryGateway#storeOnlyNew} dedups by document id. The
 * command comes from {@link VaultDocumentService#toPublishCommand(VaultDocumentEntity)}, the same
 * factory {@code publish()} uses, so the two emission sites cannot drift apart.
 *
 * <p>Each tick sweeps at most {@code lob.document_vault.dispatch.batch-size} documents, so a large
 * backlog drains across successive runs instead of materialising every stuck ciphertext at once.
 *
 * <p>Fairness comes from a retry cursor, not from status progress. {@code MARK_DISPATCH} is also a
 * legitimate resting state for a document already stored downstream, so ordering purely by
 * {@code publishedAt} would reselect the same oldest rows forever and never reach a younger document
 * whose handoff really was lost. Every selected document's {@code dispatchRetryAt} is therefore
 * stamped before the command is re-emitted, and the finder orders by that cursor with NULLS FIRST:
 * never-attempted documents sort first, and just-attempted ones rotate to the back. The sweep is
 * writable rather than {@code readOnly} so the stamp and the emission commit together, which the
 * downstream {@code AFTER_COMMIT} listener depends on.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentDispatchRetryJob {

    private final VaultDocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${lob.document_vault.dispatch.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(
            fixedDelayString = "${lob.document_vault.dispatch.fixed_delay:PT1M}",
            initialDelayString = "${lob.document_vault.dispatch.initial_delay:PT10S}")
    @Transactional
    public void reemitStuckPublishes() {
        // Unsorted: the ordering lives in the finder's JPQL so NULLS FIRST applies to one sort key.
        Pageable page = PageRequest.of(0, batchSize);
        List<VaultDocumentEntity> stuck = documentRepository.findByStatusAndLedgerDispatchStatus(
                VaultDocumentStatus.PUBLISHED, LedgerDispatchStatus.MARK_DISPATCH, page);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("document_vault dispatch retry re-emitting {} stuck publish command(s)", stuck.size());
        // Stamp before emitting, in the same transaction, so the row rotates to the back of the next
        // sweep whether or not ledgerDispatchStatus advances.
        LocalDateTime attemptedAt = LocalDateTime.now();
        stuck.forEach(document -> {
            document.setDispatchRetryAt(attemptedAt);
            documentRepository.save(document);
            eventPublisher.publishEvent(VaultDocumentService.toPublishCommand(document));
        });
    }

}
