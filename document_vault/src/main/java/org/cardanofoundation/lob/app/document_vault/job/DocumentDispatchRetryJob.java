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
 * Recovery sweep for the publish handoff (Codex adversarial-review finding 1).
 * {@link VaultDocumentService#publish(String)} commits the vault row to
 * {@code PUBLISHED}/{@code MARK_DISPATCH} and then fires an in-memory {@code DocumentPublishCommand};
 * {@code BlockchainPublisherEventHandler#handleDocumentPublishCommand} is the only consumer. If the
 * process crashes — or the async executor rejects the task — after the vault transaction commits but
 * before the publisher row is stored, the document is stuck: PUBLISHED is a permanent lock (no
 * edit/delete/purge ever again, see {@code VaultDocumentRepository#deleteByStatusAndCreatedAtBefore}
 * and {@code VaultDocumentService#delete}), yet there is no dispatch record and nothing left to retry
 * the hand-off. This job closes that gap by periodically re-querying for documents still sitting in
 * {@code PUBLISHED}/{@code MARK_DISPATCH} and re-emitting the command for each.
 *
 * <p>Re-emission is safe even when nothing actually went wrong — e.g. the first command is simply
 * still in flight when this job runs: {@code DocumentEntityRepositoryGateway#storeOnlyNew} dedups by
 * documentId downstream, so a redundant re-emit while a document legitimately waits in MARK_DISPATCH
 * is a no-op.
 *
 * <p>The command is built via {@link VaultDocumentService#toPublishCommand(VaultDocumentEntity)} —
 * the exact same factory {@code publish()} uses — so the two emission sites cannot drift apart.
 *
 * <p>Scheduling shape mirrors funding's {@code EventPublishJob} and
 * accounting_reporting_core's {@code DispatcherJob} ({@code fixedDelayString}/{@code
 * initialDelayString}, both configurable, both defaulted). Component-scanned only when {@code
 * lob.document_vault.enabled=true} (see {@code DocumentVaultModuleConfig}), the same as {@code
 * DocumentRetentionJob} — and, like that job, inert unless the consuming application also enables
 * Spring scheduling ({@code @EnableScheduling}).
 *
 * <p><b>Bounded sweep (Codex adversarial-review finding 2 of round 2):</b> a naive "load every stuck
 * document" query materializes every match's ciphertext in one go, which grows unbounded against a
 * large backlog. Each tick instead pages at most {@code lob.document_vault.dispatch.batch-size}
 * (default 50) documents. This does not need to be exhaustive per tick: {@code
 * DocumentEntityRepositoryGateway#storeOnlyNew} dedups re-emissions downstream by documentId, so a
 * bounded sweep simply spreads a large backlog across successive runs rather than risking one tick
 * doing unbounded work.
 *
 * <p><b>Retry-cursor fairness, not status-advancement fairness (Codex adversarial-review finding,
 * round 3 — retry-sweep starvation):</b> {@code PUBLISHED}/{@code MARK_DISPATCH} is a legitimate
 * resting state while a document is already stored downstream and simply awaiting further dispatch
 * progress, not only a crash symptom — so a row can sit at {@code MARK_DISPATCH} for a long time
 * without anything being wrong. A bounded sweep ordered purely by {@code publishedAt} would reselect
 * the same oldest rows every tick forever, and a younger document whose in-memory handoff really was
 * lost would never be retried. Each tick now stamps every selected document's {@code
 * dispatchRetryAt} with the sweep time — BEFORE the command is (re-)emitted, in the same transaction
 * as the read — and {@code VaultDocumentRepository#findByStatusAndLedgerDispatchStatus} orders
 * {@code dispatchRetryAt} ascending with NULLS FIRST ahead of {@code publishedAt}. A never-yet-
 * attempted document (NULL cursor) therefore always sorts first, and rows already attempted this
 * sweep or a previous one rotate to the back of the queue regardless of whether {@code
 * ledgerDispatchStatus} ever advances off {@code MARK_DISPATCH} — fairness is guaranteed by the
 * cursor, never by waiting for status progress this job cannot observe or control. This is also why
 * the sweep is now a genuine (writable) {@code @Transactional} method rather than {@code
 * readOnly = true}: the cursor stamp and the command emission must commit atomically, so the
 * downstream {@code @TransactionalEventListener(phase = AFTER_COMMIT)} consumer only ever fires once
 * the mark is durably persisted.
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
        // Unsorted: ordering is baked into the finder's JPQL (dispatchRetryAt NULLS FIRST, then
        // publishedAt) so the NULLS-FIRST clause applies to exactly one sort key.
        Pageable page = PageRequest.of(0, batchSize);
        List<VaultDocumentEntity> stuck = documentRepository.findByStatusAndLedgerDispatchStatus(
                VaultDocumentStatus.PUBLISHED, LedgerDispatchStatus.MARK_DISPATCH, page);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("document_vault dispatch retry re-emitting {} stuck publish command(s)", stuck.size());
        // Mark BEFORE emitting, same transaction: stamps the retry cursor so this row rotates to the
        // back of the next sweep's ordering regardless of whether ledgerDispatchStatus ever advances.
        LocalDateTime attemptedAt = LocalDateTime.now();
        stuck.forEach(document -> {
            document.setDispatchRetryAt(attemptedAt);
            documentRepository.save(document);
            eventPublisher.publishEvent(VaultDocumentService.toPublishCommand(document));
        });
    }

}
