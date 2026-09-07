package org.cardanofoundation.lob.app.blockchain_publisher.service.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Batch;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class CardanoDispatcherTest {

    @Mock
    private OrganisationPublicApi organisationPublicApi;
    @Mock
    private TransactionSubmissionService transactionSubmissionService;
    @Mock
    private L1SubmissionDataUpdater l1SubmissionDataUpdater;

    @InjectMocks
    private CardanoDispatcher dispatcher;

    @Mock
    private CardanoPublishable<TransactionEntity> module;

    private static final int DEFAULT_BATCH = 50;

    private Organisation org() {
        Organisation organisation = new Organisation();
        organisation.setId("org1");
        return organisation;
    }

    @Test
    void noOrganisations() {
        when(organisationPublicApi.listAll()).thenReturn(List.of());

        dispatcher.dispatch(module);

        verify(organisationPublicApi).listAll();
        verify(module, never()).claimReadyToDispatch(anyString(), anyInt());
        verifyNoInteractions(transactionSubmissionService);
    }

    @Test
    void nothingClaimed_skipsLoadingAndDispatch() {
        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(module.claimReadyToDispatch("org1", DEFAULT_BATCH)).thenReturn(Set.of());

        dispatcher.dispatch(module);

        verify(module, never()).loadByIds(any());
        verify(module, never()).buildL1Transaction(anyString(), any());
        verify(module, never()).unlock(any());
        verifyNoInteractions(transactionSubmissionService);
    }

    @Test
    void buildProblem_appliesFailureNotifiesAndUnlocks() {
        TransactionEntity tx = TransactionEntity.builder().id("tx1").build();
        L1SubmissionData failureData = L1SubmissionData.builder().publishRetry(1L).publishStatus(BlockchainPublishStatus.STORED).build();

        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(module.claimReadyToDispatch("org1", DEFAULT_BATCH)).thenReturn(Set.of("tx1"));
        when(module.loadByIds(Set.of("tx1"))).thenReturn(Set.of(tx));
        when(module.groupForDispatch(Set.of(tx))).thenReturn(List.of(Set.of(tx)));
        when(module.buildL1Transaction("org1", Set.of(tx)))
                .thenReturn(Either.left(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "boom")));
        when(l1SubmissionDataUpdater.applyFailure(any(), eq("boom"))).thenReturn(failureData);

        dispatcher.dispatch(module);

        verify(l1SubmissionDataUpdater).applyFailure(any(), eq("boom"));
        verify(module).store(tx);
        verify(module).notifyLedgerUpdate("org1", Set.of(tx));
        verify(module).unlock(Set.of(tx));
        verifyNoInteractions(transactionSubmissionService);
    }

    @Test
    void buildEmpty_appliesFailureAndNotifies() {
        TransactionEntity tx = TransactionEntity.builder().id("tx1").build();
        L1SubmissionData failureData = L1SubmissionData.builder().publishRetry(1L).build();

        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(module.claimReadyToDispatch("org1", DEFAULT_BATCH)).thenReturn(Set.of("tx1"));
        when(module.loadByIds(Set.of("tx1"))).thenReturn(Set.of(tx));
        when(module.groupForDispatch(Set.of(tx))).thenReturn(List.of(Set.of(tx)));
        when(module.buildL1Transaction("org1", Set.of(tx))).thenReturn(Either.right(Optional.empty()));
        when(l1SubmissionDataUpdater.applyFailure(any(), eq("Empty response"))).thenReturn(failureData);

        dispatcher.dispatch(module);

        verify(l1SubmissionDataUpdater).applyFailure(any(), eq("Empty response"));
        verify(module).store(tx);
        verify(module).notifyLedgerUpdate("org1", Set.of(tx));
        verify(module).unlock(Set.of(tx));
        verifyNoInteractions(transactionSubmissionService);
    }

    @Test
    void success_setsSubmittedStoresNotifiesAndUnlocks() throws ApiException {
        TransactionEntity tx = TransactionEntity.builder().id("tx1").build();
        byte[] data = new byte[]{1, 2, 3};
        L1Batch<TransactionEntity> batch = new L1Batch<>("org1", Set.of(tx), Set.of(), 42L, data, "receiver");

        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(module.claimReadyToDispatch("org1", DEFAULT_BATCH)).thenReturn(Set.of("tx1"));
        when(module.loadByIds(Set.of("tx1"))).thenReturn(Set.of(tx));
        when(module.groupForDispatch(Set.of(tx))).thenReturn(List.of(Set.of(tx)));
        when(module.buildL1Transaction("org1", Set.of(tx))).thenReturn(Either.right(Optional.of(batch)));
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(data, "receiver"))
                .thenReturn(new L1Submission("hash1", Optional.of(7L), true));

        dispatcher.dispatch(module);

        verify(module).store(tx);
        verify(module).notifyLedgerUpdate("org1", Set.of(tx));
        verify(module).unlock(Set.of(tx));

        L1SubmissionData submissionData = tx.getL1SubmissionData().orElseThrow();
        assertEquals(Optional.of(BlockchainPublishStatus.SUBMITTED), submissionData.getPublishStatus());
        assertEquals(Optional.of("hash1"), submissionData.getTransactionHash());
        assertEquals(Optional.of(7L), submissionData.getAbsoluteSlot());
        assertEquals(Optional.of(42L), submissionData.getCreationSlot());
    }

    @Test
    void submissionException_appliesFailureAndUnlocks() throws ApiException {
        TransactionEntity tx = TransactionEntity.builder().id("tx1").build();
        byte[] data = new byte[]{1};
        L1Batch<TransactionEntity> batch = new L1Batch<>("org1", Set.of(tx), Set.of(), 42L, data, "receiver");
        L1SubmissionData failureData = L1SubmissionData.builder().publishRetry(1L).build();

        when(organisationPublicApi.listAll()).thenReturn(List.of(org()));
        when(module.claimReadyToDispatch("org1", DEFAULT_BATCH)).thenReturn(Set.of("tx1"));
        when(module.loadByIds(Set.of("tx1"))).thenReturn(Set.of(tx));
        when(module.groupForDispatch(Set.of(tx))).thenReturn(List.of(Set.of(tx)));
        when(module.buildL1Transaction("org1", Set.of(tx))).thenReturn(Either.right(Optional.of(batch)));
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(data, "receiver")).thenThrow(new ApiException("submit failed"));
        lenient().when(l1SubmissionDataUpdater.applyFailure(any(), anyString())).thenReturn(failureData);

        dispatcher.dispatch(module);

        verify(l1SubmissionDataUpdater).applyFailure(any(), any());
        verify(module).store(tx);
        verify(module).notifyLedgerUpdate("org1", Set.of(tx));
        verify(module).unlock(Set.of(tx));
    }

}
