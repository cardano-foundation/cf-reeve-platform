package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
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

        transactionReconcilationService.createReconcilation(reconcilationId, organisationId, fromDate, toDate, ExtractorType.NETSUITE);

        ArgumentCaptor<ReconcilationEntity> reconcilationCaptor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(reconcilationCaptor.capture());

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

        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
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
        txEntity1.setInternalTransactionNumber("internal1");

        val txEntity2 = new TransactionEntity();
        txEntity2.setId("tx2");
        txEntity2.setInternalTransactionNumber("internal2");

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
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getId()).isEqualTo(reconcilationId);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    @Test
    void testReconcileChunk_csvExtractorTypeShouldBypassSourceReconciliation() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create organisation for both transactions
        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        // Create attached transaction with CSV extractor type
        val attachedTx = new TransactionEntity();
        attachedTx.setId("tx1");
        attachedTx.setInternalTransactionNumber("internal1");
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());

        // Create detached transaction (different to trigger hash mismatch)
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1-different");
        detachedTx.setExtractorType(ExtractorType.CSV.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        val detachedChunkTxs = Set.of(detachedTx);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of(
                "tx1", true
        )));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, detachedChunkTxs);

        // Verify the attached transaction has OK source reconciliation despite hash mismatch
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);

        verify(transactionRepositoryGateway).storeAll(List.of(attachedTx));
    }

    @Test
    void testWrapUpReconcilation_csvExtractorTypeShouldGetOkStatus() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setFrom(Optional.of(fromDate));
        reconcilationEntity.setTo(Optional.of(toDate));
        reconcilationEntity.setProcessedTxCount(0L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create missing transaction with CSV extractor type
        val missingTx = new TransactionEntity();
        missingTx.setId("tx1");
        missingTx.setInternalTransactionNumber("internal1");
        missingTx.setExtractorType(ExtractorType.CSV.name());

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // Verify the missing transaction with CSV type gets OK source reconciliation
        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(missingTx.getLastReconcilation()).isPresent();

        // Verify no violations were added for the CSV transaction
        assertThat(reconcilationEntity.getViolations()).isEmpty();
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    @Test
    void testWrapUpReconcilation_existingOkSourceReconciliationShouldRemainOk() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setFrom(Optional.of(fromDate));
        reconcilationEntity.setTo(Optional.of(toDate));
        reconcilationEntity.setProcessedTxCount(0L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create missing transaction with existing OK source reconciliation (non-CSV)
        val missingTx = new TransactionEntity();
        missingTx.setId("tx1");
        missingTx.setInternalTransactionNumber("internal1");
        missingTx.setExtractorType(ExtractorType.NETSUITE.name());
        missingTx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // Verify the missing transaction with existing OK keeps OK source reconciliation
        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(missingTx.getLastReconcilation()).isPresent();

        // Verify no violations were added
        assertThat(reconcilationEntity.getViolations()).isEmpty();
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    @Test
    void testWrapUpReconcilation_nonCsvWithoutOkReconciliationShouldGetNok() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setFrom(Optional.of(fromDate));
        reconcilationEntity.setTo(Optional.of(toDate));
        reconcilationEntity.setProcessedTxCount(0L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create missing transaction without CSV extractor type and without OK reconciliation
        val missingTx = new TransactionEntity();
        missingTx.setId("tx1");
        missingTx.setInternalTransactionNumber("internal1");
        missingTx.setExtractorType(ExtractorType.NETSUITE.name());
        missingTx.setReconcilation(Optional.empty());

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // Verify the missing transaction gets NOK reconciliation
        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(missingTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);

        // Verify violation was added
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

}
