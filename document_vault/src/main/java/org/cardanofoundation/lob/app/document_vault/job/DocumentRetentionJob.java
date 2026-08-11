package org.cardanofoundation.lob.app.document_vault.job;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * Hard-deletes DRAFT envelopes older than the configured window. Disabled by default
 * ({@code lob.document_vault.retention-days=0}), and inert unless the consuming application enables
 * Spring scheduling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRetentionJob {

    private final VaultDocumentRepository documentRepository;

    @Value("${lob.document_vault.retention-days:0}")
    private long retentionDays;

    @Scheduled(cron = "${lob.document_vault.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeExpiredDocuments() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        // DRAFT only: published documents are anchored on IPFS and L1 and are never purged.
        long deleted = documentRepository.deleteByStatusAndCreatedAtBefore(VaultDocumentStatus.DRAFT, cutoff);
        if (deleted > 0) {
            log.info("document_vault retention purged {} draft envelopes older than {} days", deleted, retentionDays);
        }
    }
}
