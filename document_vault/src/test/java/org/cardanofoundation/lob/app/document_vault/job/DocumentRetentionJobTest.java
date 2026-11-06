package org.cardanofoundation.lob.app.document_vault.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

@ExtendWith(MockitoExtension.class)
class DocumentRetentionJobTest {

    @Mock
    private VaultDocumentRepository documentRepository;

    @InjectMocks
    private DocumentRetentionJob job;

    @Test
    void disabledByDefaultDoesNothing() {
        ReflectionTestUtils.setField(job, "retentionDays", 0L);

        job.purgeExpiredDocuments();

        verifyNoInteractions(documentRepository);
    }

    @Test
    void purgesDocumentsOlderThanTheRetentionWindow() {
        ReflectionTestUtils.setField(job, "retentionDays", 30L);
        when(documentRepository.deleteByStatusAndCreatedAtBefore(any(), any())).thenReturn(2L);

        job.purgeExpiredDocuments();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).deleteByStatusAndCreatedAtBefore(
                org.mockito.ArgumentMatchers.eq(org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus.DRAFT),
                cutoff.capture());
        Assertions.assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(29)));
        Assertions.assertTrue(cutoff.getValue().isAfter(LocalDateTime.now().minusDays(31)));
    }
}
