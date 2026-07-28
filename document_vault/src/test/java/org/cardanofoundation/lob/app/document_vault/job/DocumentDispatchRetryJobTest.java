package org.cardanofoundation.lob.app.document_vault.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.events.DocumentPublishCommand;
import org.cardanofoundation.lob.app.document_vault.domain.entity.DocumentSlot;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;

/**
 * Codex adversarial-review finding 1: pins the recovery sweep that closes the "crash/async-rejection
 * after the vault TX commits but before the publisher row is stored" gap in
 * {@code VaultDocumentService#publish}. Also covers round 3's retry-cursor fairness fix (starvation
 * regression: real-Postgres coverage of the actual multi-sweep rotation lives in
 * {@code DocumentDispatchRetryJobStarvationIntegrationTest}).
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
        doc.setSlots(List.of(new DocumentSlot("k1", "me", HEX64, HEX96, "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae")));
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

        // retry-cursor fairness (round 3): every attempted document is stamped, not just re-emitted
        assertNotNull(doc1.getDispatchRetryAt(), "attempted document must get a non-null retry cursor");
        assertNotNull(doc2.getDispatchRetryAt(), "attempted document must get a non-null retry cursor");
        verify(documentRepository).save(doc1);
        verify(documentRepository).save(doc2);
    }

    @Test
    void marksTheRetryCursorBeforeEmittingTheCommandForEachDocument() {
        VaultDocumentEntity doc = stuckDoc("doc1", "org1", new byte[] {1, 2, 3});
        when(documentRepository.findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), any(Pageable.class)))
                .thenReturn(List.of(doc));

        job.reemitStuckPublishes();

        // "mark before emitting" (round 3 fix): by the time save() runs, dispatchRetryAt is already
        // non-null, and save() happens-before the event is published for that same document.
        InOrder inOrder = Mockito.inOrder(documentRepository, eventPublisher);
        inOrder.verify(documentRepository).save(doc);
        inOrder.verify(eventPublisher).publishEvent(any(DocumentPublishCommand.class));
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
    void sweepIsBoundedByTheConfiguredBatchSizeAndUnsorted() {
        ReflectionTestUtils.setField(job, "batchSize", 7);
        when(documentRepository.findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), any(Pageable.class)))
                .thenReturn(List.of());

        job.reemitStuckPublishes();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findByStatusAndLedgerDispatchStatus(
                eq(VaultDocumentStatus.PUBLISHED), eq(LedgerDispatchStatus.MARK_DISPATCH), pageableCaptor.capture());

        // unsorted (round 3): ordering — including the NULLS FIRST clause on dispatchRetryAt — now
        // lives in the finder's JPQL, not in a Sort folded into the Pageable.
        assertEquals(PageRequest.of(0, 7), pageableCaptor.getValue());
    }

    /**
     * Codex adversarial-review finding, round 3 (retry-sweep starvation): pins that the sweep stays
     * a genuine writable transaction. If this ever regressed back to {@code readOnly = true}, the
     * {@code dispatchRetryAt} stamp would either fail to persist or (worse) silently not participate
     * in the same commit as the emitted event, reopening the starvation the retry cursor exists to
     * close — see {@code DocumentDispatchRetryJob}'s class javadoc.
     */
    @Test
    void reemitStuckPublishesStaysScheduledAndWritableTransactional() throws NoSuchMethodException {
        Method method = DocumentDispatchRetryJob.class.getMethod("reemitStuckPublishes");

        assertNotNull(method.getAnnotation(Scheduled.class), "must stay @Scheduled to run periodically.");

        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "must stay @Transactional so the mark and the emit commit atomically.");
        assertFalse(transactional.readOnly(),
                "must NOT be readOnly: the sweep now writes the dispatchRetryAt cursor before emitting, "
                        + "and that write must commit in the same transaction as the read for the retry "
                        + "cursor's fairness guarantee to hold.");
    }
}
