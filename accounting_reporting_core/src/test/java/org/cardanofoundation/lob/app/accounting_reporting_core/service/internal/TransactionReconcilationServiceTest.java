package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.springframework.context.ApplicationEventPublisher;

import io.vavr.control.Either;
import org.javers.core.Javers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

class TransactionReconcilationServiceTest {

    @Mock
    private TransactionReconcilationRepository transactionReconcilationRepository;

    @Mock
    private TransactionRepositoryGateway transactionRepositoryGateway;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private BlockchainReaderPublicApiIF blockchainReaderPublicApi;

    @Mock
    private Javers javers;

    @InjectMocks
    private TransactionReconcilationService transactionReconcilationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testFindById_shouldReturnReconcilationEntity() {
        String reconcilationId = "reconcilation123";
        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setId(reconcilationId);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        Optional<ReconcilationEntity> result = transactionReconcilationService.findById(reconcilationId);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(reconcilationId);
    }

    @Test
    void testFindById_shouldReturnEmptyIfNotFound() {
        String reconcilationId = "reconcilation123";

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.empty());

        Optional<ReconcilationEntity> result = transactionReconcilationService.findById(reconcilationId);

        assertThat(result).isNotPresent();
    }

    @Test
    void testCreateReconcilation_shouldSaveReconcilationAndPublishEvent() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        transactionReconcilationService.createReconcilation(reconcilationId, organisationId, fromDate, toDate);

        ArgumentCaptor<ReconcilationEntity> reconcilationCaptor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).save(reconcilationCaptor.capture());

        assertThat(reconcilationCaptor.getValue().getId()).isEqualTo(reconcilationId);
        assertThat(reconcilationCaptor.getValue().getOrganisationId()).isEqualTo(organisationId);
        assertThat(reconcilationCaptor.getValue().getFrom()).contains(fromDate);
        assertThat(reconcilationCaptor.getValue().getTo()).contains(toDate);

        verify(applicationEventPublisher, times(1)).publishEvent(any(ReconcilationCreatedEvent.class));
    }

    @Test
    void testFailReconcilation_shouldSaveReconcilationAsFailed() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();
        FatalError fatalError = new FatalError(FatalError.Code.ADAPTER_ERROR, "Test Error", Map.of());

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        transactionReconcilationService.failReconcilation(reconcilationId, organisationId, Optional.of(fromDate), Optional.of(toDate), fatalError);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.FAILED);
        assertThat(reconcilationEntity.getDetails().get().getCode()).isEqualTo(fatalError.getCode().name());

        verify(transactionReconcilationRepository).save(reconcilationEntity);
    }

    @Test
    void testReconcileChunk_shouldAddViolationsForMissingTransactions() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val txEntity1 = new TransactionEntity();
        txEntity1.setId("tx1");
        txEntity1.setTransactionInternalNumber("internal1");

        val txEntity2 = new TransactionEntity();
        txEntity2.setId("tx2");
        txEntity2.setTransactionInternalNumber("internal2");

        val detachedChunkTxs = Set.of(txEntity1, txEntity2);
        val txIds = Set.of("tx1", "tx2");

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(txEntity1));

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of(
                "tx1", true,
                "tx2", true
        )));

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(txEntity1));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, detachedChunkTxs);

        verify(transactionReconcilationRepository).save(reconcilationEntity);
    }

    @Test
    void testWrapUpReconcilation_shouldSetReconcilationAsCompleted() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setFrom(Optional.of(LocalDate.now().minusDays(5)));
        reconcilationEntity.setTo(Optional.of(LocalDate.now()));
        reconcilationEntity.setProcessedTxCount(10L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId,10L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        verify(transactionRepositoryGateway).storeAll(any());
    }

    @Test
    void testFailReconcilationWhenEntityNotFound_shouldCreateNewAndFail() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();
        FatalError fatalError = new FatalError(FatalError.Code.ADAPTER_ERROR, "Test Error", Map.of());

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.empty());

        transactionReconcilationService.failReconcilation(reconcilationId, organisationId, Optional.of(fromDate), Optional.of(toDate), fatalError);

        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).save(captor.capture());

        assertThat(captor.getValue().getId()).isEqualTo(reconcilationId);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

}
