package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.repository.ValidateIngestionResponseRepository;

/**
 * Removes responses nobody read because they arrived after the requester timed out. Running on every
 * replica is harmless: the delete is idempotent, so this needs no claim.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ValidateIngestionResponseCleanupJob {

    private final ValidateIngestionResponseRepository repository;
    private final Clock clock;

    @Value("${lob.accounting_reporting_core.validate-ingestion.retention:PT1H}")
    private Duration retention = Duration.ofHours(1);

    @Scheduled(
            fixedDelayString = "${lob.accounting_reporting_core.validate-ingestion.cleanup-delay:PT15M}",
            initialDelayString = "${lob.accounting_reporting_core.validate-ingestion.cleanup-delay:PT15M}")
    public void execute() {
        int deleted = repository.deleteCreatedBefore(Instant.now(clock).minus(retention));
        if (deleted > 0) {
            log.info("Removed {} unread validation responses", deleted);
        }
    }

}
