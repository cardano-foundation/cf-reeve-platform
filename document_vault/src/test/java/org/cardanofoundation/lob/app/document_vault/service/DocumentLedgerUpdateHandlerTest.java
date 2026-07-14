package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.BlockchainReceipt;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerStatusUpdate;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdateType;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerUpdatedEvent;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.events.DocumentPublishedEvent;
import org.cardanofoundation.lob.app.document_vault.repository.VaultDocumentRepository;
import org.cardanofoundation.lob.app.document_vault.repository.VaultKeyRepository;

@ExtendWith(MockitoExtension.class)
class DocumentLedgerUpdateHandlerTest {

    @Mock
    private VaultDocumentRepository documentRepository;
    @Mock
    private VaultKeyRepository keyRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DocumentLedgerUpdateHandler handler;

    private LedgerUpdatedEvent event(LedgerUpdateType type, LedgerDispatchStatus status, Set<BlockchainReceipt> receipts) {
        return LedgerUpdatedEvent.builder()
                .organisationId("org1")
                .type(type)
                .statusUpdates(Set.of(new LedgerStatusUpdate("doc1", status, null, receipts)))
                .build();
    }

    @Test
    void ignoresNonDocumentUpdates() {
        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.REPORT, LedgerDispatchStatus.DISPATCHED, Set.of()));

        verifyNoInteractions(documentRepository);
    }

    @Test
    void mapsReceiptsToTxHashAndCid() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.DISPATCHED,
                Set.of(new BlockchainReceipt("CARDANO_L1", "tx-hash-1"),
                        new BlockchainReceipt("IPFS", "bafy-cid-1"))));

        assertEquals("tx-hash-1", doc.getTxHash());
        assertEquals("bafy-cid-1", doc.getIpfsCid());
        assertEquals(LedgerDispatchStatus.DISPATCHED, doc.getLedgerDispatchStatus());
        verify(documentRepository).save(doc);
    }

    @Test
    void finalizedUpdateFiresPublishedEvent() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(keyRepository.findAllById(any())).thenReturn(List.of());

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.FINALIZED, Set.of()));

        verify(eventPublisher).publishEvent(any(DocumentPublishedEvent.class));
    }

    @Test
    void repeatedFinalizedUpdateDoesNotRefireThePublishedEvent() {
        VaultDocumentEntity doc = new VaultDocumentEntity();
        doc.setId("doc1");
        doc.setOrganisationId("org1");
        doc.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED); // already finalized earlier
        when(documentRepository.findById("doc1")).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(VaultDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        handler.handleLedgerUpdatedEvent(event(LedgerUpdateType.DOCUMENT, LedgerDispatchStatus.FINALIZED, Set.of()));

        verifyNoInteractions(eventPublisher);
    }
}
