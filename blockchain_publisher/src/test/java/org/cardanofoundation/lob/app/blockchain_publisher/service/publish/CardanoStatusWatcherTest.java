package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.ChainTip;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class CardanoStatusWatcherTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;
    @Mock
    private L1SubmissionDataUpdater l1SubmissionDataUpdater;
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private CardanoStatusWatcher watcher;

    @Mock
    private CardanoPublishable<TransactionEntity> module;

    private Organisation org() {
        Organisation organisation = new Organisation();
        organisation.setId("org1");
        return organisation;
    }

    @Test
    void noOrganisations() {
        when(organisationPublicApi.listAll()).thenReturn(List.of());

        watcher.checkStatuses(module, 10);

        verify(organisationPublicApi).listAll();
        verifyNoInteractions(blockchainReaderPublicApi);
        verifyNoInteractions(module);
    }

    @Test
    void chainNotSynced_skips() {
        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(false).build()));

        watcher.checkStatuses(module, 10);

        verify(blockchainReaderPublicApi).getChainTip();
        verify(module, never()).findNotFinalizedYet(anyString(), any());
        verifyNoInteractions(l1SubmissionDataUpdater);
    }

    @Test
    void nothingPending_doesNotStoreOrNotify() {
        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(true).absoluteSlot(2L).build()));
        when(module.findNotFinalizedYet("org1", Limit.of(10))).thenReturn(Set.of());

        watcher.checkStatuses(module, 10);

        verify(module).findNotFinalizedYet("org1", Limit.of(10));
        verify(module, never()).storeAll(any());
        verify(module, never()).notifyLedgerUpdate(anyString(), any());
    }

    @Test
    void pending_updatesStoresAndNotifies() {
        TransactionEntity tx = TransactionEntity.builder()
                .id("tx1")
                .l1SubmissionData(L1SubmissionData.builder().creationSlot(1L).transactionHash("txHash").build())
                .build();

        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(blockchainReaderPublicApi.getChainTip()).thenReturn(Either.right(ChainTip.builder().isSynced(true).absoluteSlot(2L).build()));
        when(module.findNotFinalizedYet("org1", Limit.of(10))).thenReturn(Set.of(tx));
        when(l1SubmissionDataUpdater.updateFromChain(any(), any())).thenReturn(L1SubmissionData.builder().build());

        watcher.checkStatuses(module, 10);

        verify(l1SubmissionDataUpdater).updateFromChain(any(), any());
        verify(module).storeAll(Set.of(tx));
        verify(module).notifyLedgerUpdate("org1", Set.of(tx));
    }

}
