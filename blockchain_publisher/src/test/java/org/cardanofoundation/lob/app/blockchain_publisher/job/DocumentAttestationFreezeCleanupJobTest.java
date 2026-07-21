package org.cardanofoundation.lob.app.blockchain_publisher.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.documents.DocumentAttestationFreezeEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.DocumentAttestationFreezeRepository;
import org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi;

/**
 * {@link DocumentAttestationFreezeCleanupJob} must delete freeze rows ONLY for ceremonies {@link
 * AttestationConsumptionApi#findTerminalNonConsumedCeremonyIds} reports FAILED/EXPIRED — a {@code
 * CONSUMED} ceremony's freeze row is load-bearing for a later dispatch retry and must never be
 * touched (design §5.2/§7).
 */
class DocumentAttestationFreezeCleanupJobTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-06-15T00:00:00Z"), ZoneId.of("UTC"));

    private final DocumentAttestationFreezeRepository freezeRepository = mock(DocumentAttestationFreezeRepository.class);
    private final AttestationConsumptionApi attestationConsumptionApi = mock(AttestationConsumptionApi.class);
    private final DocumentAttestationFreezeCleanupJob job =
            new DocumentAttestationFreezeCleanupJob(freezeRepository, attestationConsumptionApi, FIXED_CLOCK);

    private static DocumentAttestationFreezeEntity freeze(String id, String documentId, String ceremonyId) {
        DocumentAttestationFreezeEntity freeze = new DocumentAttestationFreezeEntity();
        freeze.setId(id);
        freeze.setDocumentId(documentId);
        freeze.setCeremonyId(ceremonyId);
        freeze.setCreatedAt(LocalDateTime.now(FIXED_CLOCK).minusDays(8));
        return freeze;
    }

    @Test
    void sweepDoesNothingWhenNoOldFreezeRowsExist() {
        when(freezeRepository.findByCreatedAtBefore(any())).thenReturn(List.of());

        job.sweep();

        verify(attestationConsumptionApi, never()).findTerminalNonConsumedCeremonyIds(anyCollection());
        verify(freezeRepository, never()).deleteAll(anyCollection());
    }

    @Test
    void sweepDeletesOnlyFreezeRowsOfFailedOrExpiredCeremonies() {
        DocumentAttestationFreezeEntity failedCeremonyFreeze = freeze("f1", "doc-1", "cer-failed");
        DocumentAttestationFreezeEntity consumedCeremonyFreeze = freeze("f2", "doc-2", "cer-consumed");
        when(freezeRepository.findByCreatedAtBefore(any())).thenReturn(List.of(failedCeremonyFreeze, consumedCeremonyFreeze));
        // Only the FAILED/EXPIRED one comes back - CONSUMED (and anything the API can't vouch for) is
        // conservatively absent from the terminal-non-consumed report (see AttestationConsumptionApi's javadoc).
        when(attestationConsumptionApi.findTerminalNonConsumedCeremonyIds(Set.of("cer-failed", "cer-consumed")))
                .thenReturn(List.of("cer-failed"));

        job.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentAttestationFreezeEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(freezeRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(failedCeremonyFreeze);
    }

    @Test
    void sweepNeverDeletesConsumedCeremonyFreezeRows() {
        DocumentAttestationFreezeEntity consumedCeremonyFreeze = freeze("f1", "doc-1", "cer-consumed");
        when(freezeRepository.findByCreatedAtBefore(any())).thenReturn(List.of(consumedCeremonyFreeze));
        // CONSUMED (or unknown/purged) ceremonies are never reported as deletable.
        when(attestationConsumptionApi.findTerminalNonConsumedCeremonyIds(Set.of("cer-consumed")))
                .thenReturn(List.of());

        job.sweep();

        verify(freezeRepository, never()).deleteAll(anyCollection());
    }

}
