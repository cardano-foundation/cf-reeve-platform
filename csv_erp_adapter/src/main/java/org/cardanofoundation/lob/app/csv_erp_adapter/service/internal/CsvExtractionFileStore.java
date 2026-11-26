package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.entity.CsvExtractionFileEntity;
import org.cardanofoundation.lob.app.csv_erp_adapter.repository.CsvExtractionFileRepository;

/**
 * Holds an uploaded file between the event that receives it and the event that processes it. Both
 * are consumed through a shared group, so they can land on different replicas and the file has to be
 * reachable from all of them.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CsvExtractionFileStore {

    private final CsvExtractionFileRepository repository;
    private final Clock clock;

    @Value("${lob.csv.temporary-file.retention:PT1H}")
    private Duration retention = Duration.ofHours(1);

    /** Commits before returning, so a replica reacting to a subsequent event is able to read the file. */
    @Transactional
    public void put(String id, String organisationId, byte[] file) {
        repository.save(new CsvExtractionFileEntity(id, organisationId, file, Instant.now(clock)));
    }

    /** Removes the file as it is read: it holds financial data and must not outlive its processing. */
    @Transactional
    public Optional<ExtractionData> take(String id) {
        Optional<CsvExtractionFileEntity> file = repository.findForUpdateById(id);
        file.ifPresent(repository::delete);

        return file.map(entity -> new ExtractionData(entity.getOrganisationId(), entity.getFile()));
    }

    /** Removes files whose processing never started. Idempotent, so it is safe on every replica. */
    @Scheduled(
            fixedDelayString = "${lob.csv.temporary-file.cleanup-delay:PT15M}",
            initialDelayString = "${lob.csv.temporary-file.cleanup-delay:PT15M}")
    @Transactional
    public void deleteExpired() {
        int deleted = repository.deleteCreatedBefore(Instant.now(clock).minus(retention));
        if (deleted > 0) {
            log.warn("Removed {} CSV files whose processing never started", deleted);
        }
    }

}
