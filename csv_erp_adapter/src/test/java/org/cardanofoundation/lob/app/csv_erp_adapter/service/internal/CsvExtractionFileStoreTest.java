package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.entity.CsvExtractionFileEntity;
import org.cardanofoundation.lob.app.csv_erp_adapter.repository.CsvExtractionFileRepository;

class CsvExtractionFileStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    /** Stands in for the shared database; each store below stands in for one replica. */
    private final Map<String, CsvExtractionFileEntity> sharedDatabase = new HashMap<>();

    private CsvExtractionFileRepository repository;
    private CsvExtractionFileStore receivingReplica;
    private CsvExtractionFileStore processingReplica;

    @BeforeEach
    void setUp() {
        repository = mock(CsvExtractionFileRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            CsvExtractionFileEntity entity = invocation.getArgument(0);
            sharedDatabase.put(entity.getId(), entity);
            return entity;
        });
        when(repository.findForUpdateById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(sharedDatabase.get(invocation.<String>getArgument(0))));
        doAnswer(invocation -> sharedDatabase.remove(invocation.<CsvExtractionFileEntity>getArgument(0).getId()))
                .when(repository).delete(any());

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        receivingReplica = new CsvExtractionFileStore(repository, clock);
        processingReplica = new CsvExtractionFileStore(repository, clock);
    }

    @Test
    void fileStoredByOneReplicaIsAvailableToAnother() {
        receivingReplica.put("batch", "org", new byte[]{1, 2});

        Optional<ExtractionData> taken = processingReplica.take("batch");

        assertThat(taken).isPresent();
        assertThat(taken.orElseThrow().organisationId()).isEqualTo("org");
        assertThat(taken.orElseThrow().file()).containsExactly(1, 2);
    }

    @Test
    void fileCanOnlyBeTakenOnce() {
        receivingReplica.put("batch", "org", new byte[]{1});

        processingReplica.take("batch");

        assertThat(receivingReplica.take("batch")).isEmpty();
        assertThat(sharedDatabase).isEmpty();
    }

    @Test
    void takeIsEmptyForUnknownFile() {
        assertThat(processingReplica.take("unknown")).isEmpty();
    }

    @Test
    void cleanupDeletesFilesOlderThanTheRetention() {
        receivingReplica.deleteExpired();

        verify(repository).deleteCreatedBefore(NOW.minusSeconds(3600));
    }

}
