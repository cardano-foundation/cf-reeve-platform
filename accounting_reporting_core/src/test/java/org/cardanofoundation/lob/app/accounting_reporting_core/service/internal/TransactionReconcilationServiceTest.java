package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.javers.core.Javers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.reconcilation.ReconcilationRejectionCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionReconcilationRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerReconcilationServiceIF.IndexerReconcilationResult;
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

    @Mock
    private IndexerReconcilationServiceIF indexerReconcilationServiceMock;

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
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
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

        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
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
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
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

    // ============== reconcileWithIndexer tests ==============

    private void enableIndexer() {
        ReflectionTestUtils.setField(transactionReconcilationService, "indexerEnabled", true);
        ReflectionTestUtils.setField(transactionReconcilationService, "indexerReconcilationService",
                Optional.of(indexerReconcilationServiceMock));
    }

    @Test
    void testReconcileWithIndexer_shouldReturnEarlyWhenEntityNotFound() {
        enableIndexer();
        String reconcilationId = "reconcilation123";

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.empty());

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, "org123",
                LocalDate.now().minusDays(5), LocalDate.now());

        verify(transactionRepositoryGateway, never()).findAllByDateRange(anyString(), any(), any());
    }

    @Test
    void testReconcileWithIndexer_shouldReturnEarlyWhenNoAttachedTransactions() {
        enableIndexer();
        String reconcilationId = "reconcilation123";

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));
        when(transactionRepositoryGateway.findAllByDateRange(anyString(), any(), any()))
                .thenReturn(Set.of());

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, "org123",
                LocalDate.now().minusDays(5), LocalDate.now());

        verify(indexerReconcilationServiceMock, never()).reconcileWithIndexer(anyString(), any(), any(), anySet());
    }

    @Test
    void testReconcileWithIndexer_shouldReturnEarlyWhenIndexerDisabled() {
        // indexerEnabled defaults to false, indexerReconcilationService defaults to null/empty
        String reconcilationId = "reconcilation123";

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val tx = createTransactionEntity("tx1", "internal1");
        when(transactionRepositoryGateway.findAllByDateRange(anyString(), any(), any()))
                .thenReturn(Set.of(tx));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, "org123",
                LocalDate.now().minusDays(5), LocalDate.now());

        // Indexer service should not be invoked when disabled
        verify(transactionRepositoryGateway, never()).storeAll(anySet());
    }

    @Test
    void testReconcileWithIndexer_shouldHandleIndexerFailureWithNokSinkViolations() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setViolations(new LinkedHashSet<>());
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create transaction with existing NOK sink
        val tx = createTransactionEntity("tx1", "internal1");
        tx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.NOK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        Problem problem = Problem.builder()
                .withStatus(Status.SERVICE_UNAVAILABLE)
                .withDetail("Connection refused")
                .build();
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(problem));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        // Should add SINK_RECONCILATION_FAIL violation for NOK sink transaction
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
    }

    @Test
    void testReconcileWithIndexer_shouldSkipViolationWhenSinkIsOkOnFailure() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setViolations(new LinkedHashSet<>());
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Create transaction with existing OK sink - should not get violation on failure
        val tx = createTransactionEntity("tx1", "internal1");
        tx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.OK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(Problem.builder().withStatus(Status.SERVICE_UNAVAILABLE).build()));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        // No violation should be added for OK sink transaction
        assertThat(reconcilationEntity.getViolations()).isEmpty();
        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
    }

    @Test
    void testReconcileWithIndexer_shouldSetSinkOkForMatchingTransactions() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setViolations(new LinkedHashSet<>());
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val tx = createTransactionEntity("tx1", "internal1");
        tx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of(
                        "tx1", new IndexerReconcilationResult(ReconcilationCode.OK, null)
                )));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        assertThat(tx.getReconcilation()).isPresent();
        assertThat(tx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
        assertThat(tx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(tx.getLastReconcilation()).isPresent();
        assertThat(reconcilationEntity.getViolations()).isEmpty();
        verify(transactionRepositoryGateway).storeAll(anySet());
        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
    }

    @Test
    void testReconcileWithIndexer_shouldSetSinkNokAndAddViolationForNokResult() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setViolations(new LinkedHashSet<>());
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val tx = createTransactionEntity("tx1", "internal1");
        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of(
                        "tx1", new IndexerReconcilationResult(ReconcilationCode.NOK, "Amount mismatch")
                )));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        assertThat(tx.getReconcilation()).isPresent();
        assertThat(tx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
    }

    @Test
    void testReconcileWithIndexer_shouldAddViolationWhenTxNotInResults() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setViolations(new LinkedHashSet<>());
        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val tx = createTransactionEntity("tx1", "internal1");
        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        // Return empty results map - tx1 not found in indexer results
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of()));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        assertThat(tx.getReconcilation()).isPresent();
        assertThat(tx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
    }

    // ============== wrapUpReconcilation with indexer tests ==============

    @Test
    void testWrapUpReconcilation_shouldTriggerIndexerWhenCompletedAndEnabled() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setFrom(Optional.of(fromDate));
        reconcilationEntity.setTo(Optional.of(toDate));
        reconcilationEntity.setProcessedTxCount(1L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        // Mock for reconcileWithIndexer call
        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        // reconcileWithIndexer will be called but return early due to empty transactions
        verify(transactionRepositoryGateway).findAllByDateRange(organisationId, fromDate, toDate);
    }

    @Test
    void testWrapUpReconcilation_alreadyCompletedShouldTriggerIndexerWhenEnabled() {
        enableIndexer();
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.COMPLETED);
        reconcilationEntity.setFrom(Optional.of(fromDate));
        reconcilationEntity.setTo(Optional.of(toDate));
        reconcilationEntity.setProcessedTxCount(1L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // Mock for reconcileWithIndexer call
        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        // Should stay COMPLETED
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        // reconcileWithIndexer should be called
        verify(transactionRepositoryGateway).findAllByDateRange(organisationId, fromDate, toDate);
    }

    @Test
    void testWrapUpReconcilation_shouldReturnEarlyWhenTotalDoesNotMatch() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        reconcilationEntity.setStatus(ReconcilationStatus.STARTED);
        reconcilationEntity.setProcessedTxCount(10L);

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        // total=5 != processedTxCount=10, should return early
        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 5L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.STARTED);
        verify(transactionRepositoryGateway, never()).findAllByDateRangeAndNotReconciledYet(anyString(), any(), any());
    }

    @Test
    void testWrapUpReconcilation_shouldFailWhenEntityNotFound() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.empty());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // Should call failReconcilation which saves a new entity
        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    // ============== createReconcilation edge case tests ==============

    @Test
    void testCreateReconcilation_shouldSkipWhenAlreadyExists() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(new ReconcilationEntity()));

        transactionReconcilationService.createReconcilation(reconcilationId, organisationId, fromDate, toDate, ExtractorType.NETSUITE);

        // Should not save or publish event
        verify(transactionReconcilationRepository, never()).saveAndFlush(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // ============== reconcileChunk additional tests ==============

    @Test
    void testReconcileChunk_shouldFailWhenEntityNotFound() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.empty());

        val tx = new TransactionEntity();
        tx.setId("tx1");
        tx.setInternalTransactionNumber("internal1");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(tx));

        // Should call failReconcilation
        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    @Test
    void testReconcileChunk_shouldPreserveExistingSinkOk() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        // Create transaction with existing OK sink reconciliation
        val attachedTx = new TransactionEntity();
        attachedTx.setId("tx1");
        attachedTx.setInternalTransactionNumber("internal1");
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.OK)
                .build()));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Sink should be preserved as OK from existing reconciliation
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_shouldSetSinkNokWhenNoExistingReconciliation() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        // Create transaction without existing reconciliation
        val attachedTx = new TransactionEntity();
        attachedTx.setId("tx1");
        attachedTx.setInternalTransactionNumber("internal1");
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Sink should be NOK since no existing reconciliation
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
    }

    @Test
    void testReconcileChunk_shouldFailWhenBlockchainReaderFails() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        val attachedTx = new TransactionEntity();
        attachedTx.setId("tx1");
        attachedTx.setInternalTransactionNumber("internal1");
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));

        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.left(Problem.builder().withStatus(Status.SERVICE_UNAVAILABLE).build()));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Should call failReconcilation
        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    // ============== Helper methods ==============

    private TransactionEntity createTransactionEntity(String id, String internalNumber) {
        val tx = new TransactionEntity();
        tx.setId(id);
        tx.setInternalTransactionNumber(internalNumber);
        tx.setTransactionType(TransactionType.VendorPayment);
        tx.setEntryDate(LocalDate.now());
        val item = new TransactionItemEntity();
        item.setAmountLcy(BigDecimal.ZERO);
        tx.setItems(Set.of(item));
        return tx;
    }

}
