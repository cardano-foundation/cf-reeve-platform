package org.cardanofoundation.lob.app.document_vault.job;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentDispatchRetryJob {

    private final VaultDocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(
            fixedDelayString = "${lob.document_vault.dispatch.fixed_delay:PT1M}",
            initialDelayString = "${lob.document_vault.dispatch.initial_delay:PT10S}")
    @Transactional(readOnly = true)
    public void reemitStuckPublishes() {
        List<VaultDocumentEntity> stuck = documentRepository.findByStatusAndLedgerDispatchStatus(
                VaultDocumentStatus.PUBLISHED, LedgerDispatchStatus.MARK_DISPATCH);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("document_vault dispatch retry re-emitting {} stuck publish command(s)", stuck.size());
        stuck.forEach(document -> eventPublisher.publishEvent(VaultDocumentService.toPublishCommand(document)));
    }

}
