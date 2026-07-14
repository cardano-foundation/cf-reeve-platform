package org.cardanofoundation.lob.app.document_vault.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * Codex adversarial-review finding 1: pins the recovery sweep that closes the "crash/async-rejection
 * after the vault TX commits but before the publisher row is stored" gap in
 * {@code VaultDocumentService#publish}.
 */
@ExtendWith(MockitoExtension.class)
class DocumentDispatchRetryJobTest {

    private static final String HEX64 = "a".repeat(64);
    private static final String HEX96 = "b".repeat(96);

    @Mock
    private VaultDocumentRepository documentRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DocumentDispatchRetryJob job;

    private VaultDocumentEntity stuckDoc(String id, String orgId, byte[] ciphertext) {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId(id);
        doc.setOrganisationId(orgId);
        doc.setStatus(VaultDocumentStatus.PUBLISHED);
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.MARK_DISPATCH);
        doc.setEnvelopeVersion(1);
        doc.setContentHash(HEX64);
        doc.setPlaintextHash(HEX64);
        doc.setCiphertext(ciphertext);
        doc.setPayloadNonce("f".repeat(24));
        doc.setSizeBytes(ciphertext.length);
        doc.setCreatedByAccount("sender");
        doc.setSlots(List.of(new DocumentSlot("k1", "me", HEX64, HEX96)));
        return doc;
    }

    @Test
    void reemitsACommandForEveryStuckDocumentWithCorrectFields() {
        VaultDocumentEntity doc1 = stuckDoc("doc1", "org1", new byte[] {1, 2, 3});
        VaultDocumentEntity doc2 = stuckDoc("doc2", "org2", new byte[] {4, 5, 6});
        when(documentRepository.findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), any(Pageable.class)))
                .thenReturn(List.of(doc1, doc2));

        job.reemitStuckPublishes();

        ArgumentCaptor<DocumentPublishCommand> captor = ArgumentCaptor.forClass(DocumentPublishCommand.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());

        List<DocumentPublishCommand> commands = captor.getAllValues();
        DocumentPublishCommand command1 = commands.stream()
                .filter(c -> c.documentId().equals("doc1")).findFirst().orElseThrow();
        assertEquals("org1", command1.organisationId());
        assertEquals(1, command1.envelopeVersion());
        assertEquals(HEX64, command1.contentHash());
        assertEquals(HEX64, command1.plaintextHash());
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), command1.ciphertextBase64());
        assertEquals(1, command1.slots().size());

        DocumentPublishCommand command2 = commands.stream()
                .filter(c -> c.documentId().equals("doc2")).findFirst().orElseThrow();
        assertEquals("org2", command2.organisationId());
        assertEquals(Base64.getEncoder().encodeToString(new byte[] {4, 5, 6}), command2.ciphertextBase64());
    }

    @Test
    void emptyResultEmitsNothing() {
        when(documentRepository.findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), any(Pageable.class)))
                .thenReturn(List.of());

        job.reemitStuckPublishes();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void sweepIsBoundedByTheConfiguredBatchSizeOrderedByPublishedAtAscending() {
        ReflectionTestUtils.setField(job, "batchSize", 7);
        when(documentRepository.findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), any(Pageable.class)))
                .thenReturn(List.of());

        job.reemitStuckPublishes();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), pageableCaptor.capture());

        assertEquals(PageRequest.of(0, 7, Sort.by(Sort.Direction.ASC, "publishedAt")), pageableCaptor.getValue());
    }
}
