package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
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
    private ERPSourceReconciliationDiffCalculator erpDiffCalculator;

    @Mock
    private IndexerReconcilationServiceIF indexerReconcilationServiceMock;

    @Mock
    private TransactionBatchService transactionBatchService;

    @InjectMocks
    private TransactionReconcilationService transactionReconcilationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    // ============== findById tests ==============

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

    // ============== createReconcilation tests ==============

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
    void testCreateReconcilation_shouldSkipWhenAlreadyExists() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        when(transactionReconcilationRepository.findById(reconcilationId))
                .thenReturn(Optional.of(new ReconcilationEntity()));

        transactionReconcilationService.createReconcilation(reconcilationId, organisationId, fromDate, toDate, ExtractorType.NETSUITE);

        verify(transactionReconcilationRepository, never()).saveAndFlush(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // ============== failReconcilation tests ==============

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

    // ============== reconcileChunk tests ==============

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

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of()));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, detachedChunkTxs);

        verify(transactionReconcilationRepository).saveAndFlush(reconcilationEntity);
    }

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

        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    @Test
    void testReconcileChunk_csvExtractorType_setsSourceOkWhenTransactionDataMatches() {
        // CSV transactions go through the same hash-based source reconciliation as NETSUITE.
        // When attached and detached data are identical the hash comparison yields OK.
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
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());

        // detachedTx has the same ERP-relevant data → hashes match → source = OK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.CSV.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));

        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of(
                "tx1", true
        )));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        // No SOURCE_RECONCILATION_FAIL violation should be added when hashes match
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );

        verify(transactionRepositoryGateway).storeAll(List.of(attachedTx));
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
                .thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_shouldTriggerIndexerReconciliation() {
        enableIndexer();
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

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of(
                        "tx1", new IndexerReconcilationResult(ReconcilationCode.OK, null)
                )));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Indexer reconciliation is triggered inline at end of reconcileChunk
        verify(indexerReconcilationServiceMock).reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet());
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    // ============== wrapUpReconcilation tests ==============

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

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 10L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        verify(transactionRepositoryGateway).storeAll(any());
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

        ArgumentCaptor<ReconcilationEntity> captor = ArgumentCaptor.forClass(ReconcilationEntity.class);
        verify(transactionReconcilationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReconcilationStatus.FAILED);
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

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.CSV.name())
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(missingTx.getLastReconcilation()).isPresent();

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

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.NETSUITE.name())
                .reconcilation(Reconcilation.builder()
                        .source(ReconcilationCode.OK)
                        .build())
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(missingTx.getLastReconcilation()).isPresent();

        assertThat(reconcilationEntity.getViolations()).isEmpty();
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    /**
     * NEW BEHAVIOUR: When a transaction is missing in ERP and NOT ledger-dispatch-approved,
     * a TransactionViolation (TX_NOT_IN_ERP) is added directly to the transaction entity
     * instead of a ReconcilationViolation on the reconciliation entity.
     */
    @Test
    void testWrapUpReconcilation_nonCsvNotLedgerApproved_shouldAddTransactionViolationToTx() {
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

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.NETSUITE.name())
                .ledgerDispatchApproved(false)
                .transactionType(TransactionType.VendorPayment)
                .entryDate(fromDate)
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // Transaction itself should have a TX_NOT_IN_ERP violation
        assertThat(missingTx.getViolations()).hasSize(1);
        assertThat(missingTx.getViolations().iterator().next().getCode()).isEqualTo(TransactionViolationCode.TRANSACTION_NOT_IN_ERP);
        assertThat(missingTx.getViolations().iterator().next().getSource()).isEqualTo(Source.ERP);

        // No ReconcilationViolation on the reconcilationEntity for this case
        assertThat(reconcilationEntity.getViolations()).isEmpty();

        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    /**
     * When a transaction is missing in ERP and IS ledger-dispatch-approved,
     * a ReconcilationViolation (TX_NOT_IN_ERP) is added to the reconciliation entity (original behaviour).
     */
    @Test
    void testWrapUpReconcilation_nonCsvLedgerApproved_shouldAddReconcilationViolation() {
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

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.NETSUITE.name())
                .ledgerDispatchApproved(true)
                .transactionType(TransactionType.VendorPayment)
                .entryDate(fromDate)
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        // ReconcilationViolation should be added to the reconciliation entity
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.TX_NOT_IN_ERP);

        // Transaction itself should NOT have violations in this path
        assertThat(missingTx.getViolations()).isEmpty();

        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    /**
     * NEW BEHAVIOUR: After wrapUp, transactionBatchService.invokeUpdateTransactionBatchStatusAndStats
     * is called for each batch ID of the missing (non-CSV, non-OK) transactions.
     */
    @Test
    void testWrapUpReconcilation_shouldInvokeBatchServiceForMissingTxBatches() {
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

        val batch1 = new TransactionBatchEntity();
        batch1.setId("batch-001");
        val batch2 = new TransactionBatchEntity();
        batch2.setId("batch-002");

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.NETSUITE.name())
                .ledgerDispatchApproved(false)
                .transactionType(TransactionType.VendorPayment)
                .entryDate(fromDate)
                .batches(new LinkedHashSet<>(Set.of(batch1, batch2)))
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        verify(transactionBatchService).invokeUpdateTransactionBatchStatusAndStats(
                eq("batch-001"), eq(Optional.empty()), eq(Optional.empty()));
        verify(transactionBatchService).invokeUpdateTransactionBatchStatusAndStats(
                eq("batch-002"), eq(Optional.empty()), eq(Optional.empty()));
    }

    @Test
    void testWrapUpReconcilation_shouldNotInvokeBatchServiceWhenNoMissingTxs() {
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

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        verify(transactionBatchService, never()).invokeUpdateTransactionBatchStatusAndStats(any(), any(), any());
    }

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

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        // missingTxs is empty → processIndexerReconciliation returns early before calling the indexer
        verify(indexerReconcilationServiceMock, never()).reconcileWithIndexer(anyString(), any(), any(), anySet());
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

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        verify(transactionRepositoryGateway).findAllByDateRange(organisationId, fromDate, toDate);
    }

    @Test
    void testReconcileChunk_shouldAddSourceReconcilationFailViolation_whenHashMismatch() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER"); // causes hash mismatch
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        // amount differs from attachedTx → financially unequal too, so status stays NOK
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
        verify(erpDiffCalculator).computeDiff(any(), any());
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_shouldHandleIndexerApiFailure() {
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        // no existing reconciliation → getSinkReconcilationStatus returns NOK

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Indexer down")));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // tx has sink=NOK (no prior reconciliation) → SINK_RECONCILATION_FAIL violation added on indexer failure
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
        verify(indexerReconcilationServiceMock).reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet());
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_shouldMarkNokWhenTxNotInIndexerResults() {
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        // indexer returns OK but tx1 is absent from the result map
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of()));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
    }

    @Test
    void testWrapUpReconcilation_withIndexerEnabled_shouldCallIndexerForNonEmptyMissingTxs() {
        enableIndexer();
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

        val missingTx = TransactionEntity.builder()
                .id("tx1")
                .internalTransactionNumber("internal1")
                .extractorType(ExtractorType.NETSUITE.name())
                .ledgerDispatchApproved(false)
                .transactionType(TransactionType.VendorPayment)
                .entryDate(fromDate)
                .build();

        when(transactionRepositoryGateway.findAllByDateRangeAndNotReconciledYet(organisationId, fromDate, toDate))
                .thenReturn(Set.of(missingTx));
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of("tx1", new IndexerReconcilationResult(ReconcilationCode.OK, null))));

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 0L);

        verify(indexerReconcilationServiceMock).reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet());
        assertThat(missingTx.getReconcilation()).isPresent();
        assertThat(missingTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(missingTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
    }

    private void enableIndexer() {
        ReflectionTestUtils.setField(transactionReconcilationService, "indexerEnabled", true);
        ReflectionTestUtils.setField(transactionReconcilationService, "indexerReconcilationService",
                Optional.of(indexerReconcilationServiceMock));
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_shouldHandleNokResultInline() {
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);

        // detachedTx must match attachedTx fields so hashes are equal → sourceReconcilationStatus = OK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", true)));

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of(
                        "tx1", new IndexerReconcilationResult(ReconcilationCode.NOK, "Amount mismatch")
                )));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        verify(indexerReconcilationServiceMock).reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet());
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_MISMATCH);
    }

    @Test
    void testReconcileChunk_csvExtractorType_setsSourceNokWhenTransactionDataDiffers() {
        // CSV transactions use the same hash-based source reconciliation as NETSUITE.
        // When attached and detached data differ, a SOURCE_RECONCILATION_FAIL violation must be raised.
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
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.Journal);
        attachedTx.setEntryDate(fromDate);

        // detachedTx has a different internal number and amount → hashes differ and
        // financials differ too → source = NOK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1-MODIFIED");
        detachedTx.setExtractorType(ExtractorType.CSV.name());
        detachedTx.setOrganisation(organisation);
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.Journal);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).anyMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }

    // ============== rollback suffix reconciliation tests ==============

    /**
     * Regression test: when reconciliation is triggered via CSV (not NetSuite), the detached tx
     * already has the rollback suffix applied to its internalTransactionNumber (e.g. "TXNUM-C").
     * The old code read originalTxNumber from detachedTx, producing "TXNUM-C-C" — a double-suffix
     * that caused a hash mismatch. The fix derives originalTxNumber from the attached (DB) tx
     * by stripping the known rollback suffix, so the result is always "TXNUM-C" regardless of
     * whether the detached tx came from CSV (already suffixed) or NetSuite (not yet suffixed).
     */
    @Test
    void testReconcileChunk_withRollbackSuffix_csvPath_detachedAlreadySuffixed_shouldSucceed() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        String txNumber = "TXNUM";
        String rollbackSuffix = "C";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        String txId = Transaction.id(organisationId, txNumber);

        // DB (attached): CSV-imported, already has rollback suffix and CSV-style item IDs
        val attachedItem = okItem(TransactionItem.id(txNumber, "0"), BigDecimal.TEN);
        val attachedTx = new TransactionEntity();
        attachedTx.setId(txId);
        attachedTx.setInternalTransactionNumber(txNumber + "-" + rollbackSuffix);
        attachedTx.setRollbackSuffix(rollbackSuffix);
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setAllItems(new HashSet<>(Set.of(attachedItem)));
        attachedItem.setTransaction(attachedTx);

        // CSV reconciliation path: detachedTx already has rollback suffix applied
        // (TransactionConverter.rollbackTransaction() was called before reconcileChunk)
        val detachedItem = okItem(TransactionItem.id(txNumber, "0"), BigDecimal.TEN);
        val detachedTx = new TransactionEntity();
        detachedTx.setId(txId);
        detachedTx.setInternalTransactionNumber(txNumber + "-" + rollbackSuffix); // already suffixed
        detachedTx.setRollbackSuffix(rollbackSuffix);
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        detachedTx.setAllItems(new HashSet<>(Set.of(detachedItem)));
        detachedItem.setTransaction(detachedTx);

        when(transactionRepositoryGateway.findByAllId(Set.of(txId)))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of(txId, true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // internalTransactionNumber must be "TXNUM-C" (not "TXNUM-C-C") → hashes match → source = OK
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }


    /**
     * Regression test for the getAllItems() bug fix.
     * When a rollback transaction has erased items at lower line positions (k=0,1,2) and
     * the only OK item is at a higher position (k=3), the item ID remapping loop must still
     * find and remap the OK item. This requires iterating over all item positions using
     * getAllItems(), not just the count of OK items via getItems().
     */
    @Test
    void testReconcileChunk_withRollbackSuffix_okItemAtHigherPosition_shouldRemapAndSucceed() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        String txNumber = "TXNUM";
        String rollbackSuffix = "C";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        String txId = Transaction.id(organisationId, txNumber);

        // Build 4 items: k=0,1,2 are ERASED, k=3 is the only OK item (VAT-line scenario).
        // attachedTx (CSV-style IDs: SHA3(txNumber::k))
        val attachedItem0 = erasedItem(TransactionItem.id(txNumber, "0"));
        val attachedItem1 = erasedItem(TransactionItem.id(txNumber, "1"));
        val attachedItem2 = erasedItem(TransactionItem.id(txNumber, "2"));
        val attachedItem3 = okItem(TransactionItem.id(txNumber, "3"), BigDecimal.TEN);

        val attachedTx = new TransactionEntity();
        attachedTx.setId(txId);
        attachedTx.setInternalTransactionNumber(txNumber + "-" + rollbackSuffix);
        attachedTx.setRollbackSuffix(rollbackSuffix);
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setAllItems(new HashSet<>(Set.of(attachedItem0, attachedItem1, attachedItem2, attachedItem3)));
        attachedItem0.setTransaction(attachedTx);
        attachedItem1.setTransaction(attachedTx);
        attachedItem2.setTransaction(attachedTx);
        attachedItem3.setTransaction(attachedTx);

        // detachedTx (ERP-style IDs: SHA3(txId::k)) — same 3 erased + 1 OK, same content
        val detachedItem0 = erasedItem(TransactionItem.id(txId, "0"));
        val detachedItem1 = erasedItem(TransactionItem.id(txId, "1"));
        val detachedItem2 = erasedItem(TransactionItem.id(txId, "2"));
        val detachedItem3 = okItem(TransactionItem.id(txId, "3"), BigDecimal.TEN);

        val detachedTx = new TransactionEntity();
        detachedTx.setId(txId);
        detachedTx.setInternalTransactionNumber(txNumber);
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        detachedTx.setAllItems(new HashSet<>(Set.of(detachedItem0, detachedItem1, detachedItem2, detachedItem3)));
        detachedItem0.setTransaction(detachedTx);
        detachedItem1.setTransaction(detachedTx);
        detachedItem2.setTransaction(detachedTx);
        detachedItem3.setTransaction(detachedTx);

        when(transactionRepositoryGateway.findByAllId(Set.of(txId)))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of(txId, true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // After remapping the OK item from k=3 ERP-style → CSV-style, hashes match → source = OK
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }

    // ============== SOURCE_RECONCILATION_MISMATCH tests ==============

    @Test
    void testReconcileChunk_hashMismatch_ledgerApproved_shouldAddSourceReconcilationMismatch() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setLedgerDispatchApproved(true); // dispatch-approved → mismatch code

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER"); // causes hash mismatch
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        // amount differs from attachedTx → financially unequal too, so status stays NOK
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_MISMATCH);
        verify(erpDiffCalculator).computeDiff(any(), any());
    }

    @Test
    void testReconcileChunk_hashMismatch_notLedgerApproved_shouldAddSourceReconcilationFail() {
        // Existing test already covers this (testReconcileChunk_shouldAddSourceReconcilationFailViolation_whenHashMismatch),
        // but also confirm null ledgerDispatchApproved behaves same as false.
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setLedgerDispatchApproved(false);

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        // amount differs from attachedTx → financially unequal too, so status stays NOK
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    // ============== Indexer hasSourceOK gating tests ==============

    @Test
    void testReconcileChunk_withIndexerEnabled_txWithSourceNok_shouldNotAddSinkViolationFromIndexer() {
        // When a hash mismatch forces source=NOK, processTransactionIndexerResult skips indexer
        // result processing for that tx (hasSourceOK = false). Even if the indexer returns OK,
        // no SINK violation is added — only the SOURCE_RECONCILATION_FAIL violation is present.
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setLedgerDispatchApproved(false);

        // Different internal number and amount → hash mismatch and financial mismatch →
        // source=NOK → SOURCE_RECONCILATION_FAIL added
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-INTERNAL"); // causes hash mismatch
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of("tx1", new IndexerReconcilationResult(ReconcilationCode.OK, null))));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // source=NOK → indexer result not processed → sink stays NOK, no SINK violation
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        // Only SOURCE violation is present — no SINK_RECONCILATION_FAIL or SINK_RECONCILATION_MISMATCH
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_txWithSourceOk_shouldProcessIndexerResult() {
        // tx has source=OK → hasSourceOK = true → indexer result IS processed
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setReconcilation(Optional.of(
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation.builder()
                        .source(ReconcilationCode.OK)
                        .build()));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of("tx1", new IndexerReconcilationResult(ReconcilationCode.NOK, "mismatch"))));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // source=OK → indexer result processed → SINK_RECONCILATION_MISMATCH added
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_MISMATCH);
    }

    // ============== shouldAddViolationOnIndexerError gating tests ==============

    @Test
    void testReconcileChunk_withIndexerEnabled_indexerError_txSourceNok_shouldNotAddSinkViolation() {
        // When the hash mismatch makes source=NOK and the indexer also fails,
        // shouldAddViolationOnIndexerError requires source=OK → false → no SINK violation is added.
        // Only the SOURCE_RECONCILATION_FAIL violation from the hash mismatch is present.
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        val attachedItemFinancial = okItem("item-attached", BigDecimal.TEN);
        attachedItemFinancial.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItemFinancial));
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setLedgerDispatchApproved(false);

        // Different internal number and amount → hash mismatch and financial mismatch → source=NOK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-INTERNAL");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        val detachedItemFinancial = okItem("item-detached", BigDecimal.valueOf(20));
        detachedItemFinancial.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItemFinancial));
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(org.springframework.http.ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Indexer down")));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Only SOURCE_RECONCILATION_FAIL is present; no SINK_RECONCILATION_FAIL added
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_indexerError_txSinkAlreadyOk_shouldNotAddViolation() {
        // When sink was already OK from a previous reconciliation and the indexer now fails,
        // shouldAddViolationOnIndexerError requires sink≠OK → condition is false → no SINK violation.
        enableIndexer();
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

        // Pre-set sink=OK so getSinkReconcilationStatus preserves it
        val attachedTx = new TransactionEntity();
        attachedTx.setId("tx1");
        attachedTx.setInternalTransactionNumber("internal1");
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setReconcilation(Optional.of(
                org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation.builder()
                        .sink(ReconcilationCode.OK) // existing sink=OK → preserved by getSinkReconcilationStatus
                        .build()));

        // Matching internal number → hash match → source=OK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(org.springframework.http.ProblemDetail.forStatusAndDetail(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Indexer down")));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // sink=OK → shouldAddViolationOnIndexerError = false → no violation added
        assertThat(reconcilationEntity.getViolations()).isEmpty();
    }

    private static TransactionItemEntity erasedItem(String id) {
        val item = new TransactionItemEntity();
        item.setId(id);
        item.setFxRate(BigDecimal.ZERO);
        item.setAmountFcy(BigDecimal.ZERO);
        item.setAmountLcy(BigDecimal.ZERO);
        item.setOperationType(OperationType.DEBIT);
        item.setStatus(TxItemValidationStatus.ERASED_SUM_APPLIED);
        return item;
    }

    private static TransactionItemEntity okItem(String id, BigDecimal amount) {
        val item = new TransactionItemEntity();
        item.setId(id);
        item.setFxRate(BigDecimal.ONE);
        item.setAmountFcy(amount);
        item.setAmountLcy(amount);
        item.setOperationType(OperationType.DEBIT);
        item.setStatus(TxItemValidationStatus.OK);
        return item;
    }

    private static TransactionItemEntity creditItem(String id, BigDecimal amount) {
        val item = new TransactionItemEntity();
        item.setId(id);
        item.setFxRate(BigDecimal.ONE);
        item.setAmountFcy(amount);
        item.setAmountLcy(amount);
        item.setOperationType(OperationType.CREDIT);
        item.setStatus(TxItemValidationStatus.OK);
        return item;
    }

    /**
     * When attachedTx has a rollbackSuffix, reconcileChunk must append the suffix to
     * detachedTx's internalTransactionNumber before comparing hashes so that the source
     * reconciliation succeeds (source = OK) despite the suffix mismatch.
     */
    @Test
    void testReconcileChunk_withRollbackSuffix_shouldNormalizeInternalNumberAndSucceed() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        String txNumber = "TXNUM";
        String rollbackSuffix = "C";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        String txId = Transaction.id(organisationId, txNumber);

        // DB (attached): already has rollbackSuffix and the suffixed internal number
        val attachedTx = new TransactionEntity();
        attachedTx.setId(txId);
        attachedTx.setInternalTransactionNumber(txNumber + "-" + rollbackSuffix);
        attachedTx.setRollbackSuffix(rollbackSuffix);
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setItems(Set.of());

        // ERP (detached): original number without suffix, no items
        val detachedTx = new TransactionEntity();
        detachedTx.setId(txId);
        detachedTx.setInternalTransactionNumber(txNumber);
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of(txId)))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of(txId, true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }

    /**
     * When attachedTx has a rollbackSuffix and contains items with CSV-style IDs
     * (SHA3(txNumber::k)), while detachedTx items carry ERP-style IDs
     * (SHA3(transactionId::k)), reconcileChunk must remap the detached item IDs to
     * CSV-style so the hashes match and source reconciliation succeeds (source = OK).
     */
    @Test
    void testReconcileChunk_withRollbackSuffix_withItems_shouldRemapItemIdsAndSucceed() {
        String reconcilationId = "reconcilation123";
        String organisationId = "org123";
        String txNumber = "TXNUM";
        String rollbackSuffix = "C";
        LocalDate fromDate = LocalDate.now().minusDays(5);
        LocalDate toDate = LocalDate.now();

        ReconcilationEntity reconcilationEntity = new ReconcilationEntity();
        when(transactionReconcilationRepository.findReconcilationEntityById(reconcilationId))
                .thenReturn(Optional.of(reconcilationEntity));

        val organisation = org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation.builder()
                .id(organisationId)
                .build();

        String txId = Transaction.id(organisationId, txNumber);

        // Attached item uses CSV-style ID: SHA3(txNumber::0)
        val attachedItem = new TransactionItemEntity();
        attachedItem.setId(TransactionItem.id(txNumber, "0"));
        attachedItem.setFxRate(BigDecimal.ZERO);
        attachedItem.setAmountFcy(BigDecimal.ZERO);
        attachedItem.setAmountLcy(BigDecimal.ZERO);
        attachedItem.setOperationType(OperationType.DEBIT);

        val attachedTx = new TransactionEntity();
        attachedTx.setId(txId);
        attachedTx.setInternalTransactionNumber(txNumber + "-" + rollbackSuffix);
        attachedTx.setRollbackSuffix(rollbackSuffix);
        attachedTx.setExtractorType(ExtractorType.CSV.name());
        attachedTx.setLedgerDispatchStatus(LedgerDispatchStatus.FINALIZED);
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        attachedTx.setItems(new HashSet<>(Set.of(attachedItem)));
        attachedItem.setTransaction(attachedTx);

        // Detached item uses ERP-style ID: SHA3(txId::0)
        val detachedItem = new TransactionItemEntity();
        detachedItem.setId(TransactionItem.id(txId, "0"));
        detachedItem.setFxRate(BigDecimal.ZERO);
        detachedItem.setAmountFcy(BigDecimal.ZERO);
        detachedItem.setAmountLcy(BigDecimal.ZERO);
        detachedItem.setOperationType(OperationType.DEBIT);

        val detachedTx = new TransactionEntity();
        detachedTx.setId(txId);
        detachedTx.setInternalTransactionNumber(txNumber);
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        detachedTx.setItems(new HashSet<>(Set.of(detachedItem)));
        detachedItem.setTransaction(detachedTx);

        when(transactionRepositoryGateway.findByAllId(Set.of(txId)))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of(txId, true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // After remapping, item IDs match and hashes agree → source = OK
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }

    // ============== Exclusion violation sink tests ==============

    private TransactionViolation buildExclusionViolation(TransactionViolationCode code) {
        return TransactionViolation.builder()
                .code(code)
                .severity(Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("test")
                .build();
    }

    @Test
    void testReconcileChunk_txWithNetOffTxViolation_shouldSetSinkOk() {
        // NET_OFF_TX is an exclusion code → getSinkReconcilationStatus returns OK immediately,
        // overriding the default NOK path (no existing sink value).
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(buildExclusionViolation(TransactionViolationCode.NET_OFF_TX))));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_txWithTransactionNotInErpViolation_shouldSetSinkOk() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(buildExclusionViolation(TransactionViolationCode.TRANSACTION_NOT_IN_ERP))));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_txWithDocumentMustBePresentViolation_shouldSetSinkOk() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(buildExclusionViolation(TransactionViolationCode.DOCUMENT_MUST_BE_PRESENT))));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_txWithExclusionViolation_overridesExistingSinkNok() {
        // Even if the tx previously had sink=NOK, the exclusion violation forces sink=OK
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        // Pre-existing sink=NOK; exclusion violation should override this
        attachedTx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.NOK)
                .build()));
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(buildExclusionViolation(TransactionViolationCode.ALL_TX_ITEMS_ERASED))));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_withIndexerEnabled_txWithExclusionViolation_sourceOk_shouldSetSinkOkNoViolation() {
        // When indexer is enabled and a tx has an exclusion violation with matching hashes (source=OK):
        // - getSinkReconcilationStatus returns OK (exclusion takes priority)
        // - The indexer also returns OK for the excluded tx
        // - processTransactionIndexerResult: hasSourceOK=true, indexer=OK → sink stays OK
        // - No SINK violation should be added to the reconciliation entity
        enableIndexer();
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(buildExclusionViolation(TransactionViolationCode.NET_OFF_TX))));

        // detachedTx matches attachedTx → hashes equal → source=OK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));
        // The indexer returns OK for the excluded tx (as the new OnChainIndexerReconcilationService does)
        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of("tx1", new IndexerReconcilationResult(ReconcilationCode.OK, null))));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // source=OK (hash match), exclusion → sink=OK, no SINK violation
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).isEmpty();
    }

    @Test
    void testReconcileChunk_txWithBothExclusionAndNonExclusionViolations_shouldSetSinkOk() {
        // When a tx has both an exclusion violation (e.g. ALL_TX_ITEMS_ERASED) and a non-exclusion
        // violation (e.g. TX_CANNOT_BE_ALTERED), the exclusion check in getSinkReconcilationStatus
        // uses anyMatch → finds the exclusion → returns OK immediately.
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(
                buildExclusionViolation(TransactionViolationCode.ALL_TX_ITEMS_ERASED),
                TransactionViolation.builder()
                        .code(TransactionViolationCode.TX_CANNOT_BE_ALTERED)
                        .severity(Violation.Severity.ERROR)
                        .source(Source.ERP)
                        .processorModule("test")
                        .build()
        )));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Exclusion violation wins → sink=OK regardless of the non-exclusion violation
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
    }

    @Test
    void testReconcileChunk_txWithNonExclusionViolation_shouldFollowNormalSinkFlow() {
        // TX_CANNOT_BE_ALTERED is NOT in the exclusion list → normal flow applies.
        // Without existing sink value → sink = NOK.
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setItems(Set.of());
        // TX_CANNOT_BE_ALTERED is not an exclusion code
        attachedTx.setViolations(new LinkedHashSet<>(Set.of(TransactionViolation.builder()
                .code(TransactionViolationCode.TX_CANNOT_BE_ALTERED)
                .severity(Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("test")
                .build())));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1"))).thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet())).thenReturn(Either.right(Map.of("tx1", false)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        // Non-exclusion violation → normal flow → no existing sink → NOK
        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
    }

    // ============== areTransactionsFinanciallyEqual fallback tests ==============
    // New behaviour: when the ERP-source hash differs, the transactions are re-checked by
    // comparing summed CREDIT and DEBIT amountLcy. If those sums match, the mismatch is
    // considered a non-financial (e.g. metadata/ID) diff and source is treated as OK.

    @Test
    void testReconcileChunk_hashMismatch_financiallyEqualDebitSums_shouldSetSourceOkAndSkipDiff() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        val attachedItem = okItem("item-attached", BigDecimal.TEN);
        attachedItem.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItem));

        // Different internal number → hash mismatch, but same DEBIT sum (10) → financially equal
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        val detachedItem = okItem("item-detached", BigDecimal.TEN);
        detachedItem.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItem));

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
        // No diff should be computed since financial fallback already resolved the mismatch as OK
        verify(erpDiffCalculator, never()).computeDiff(any(), any());
    }

    @Test
    void testReconcileChunk_hashMismatch_creditSumsDiffer_shouldRemainNok() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        val attachedItem = creditItem("item-attached", BigDecimal.valueOf(100));
        attachedItem.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedItem));

        // Different internal number → hash mismatch, and CREDIT sums differ (100 vs 50) → financially unequal
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        val detachedItem = creditItem("item-detached", BigDecimal.valueOf(50));
        detachedItem.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedItem));

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
        verify(erpDiffCalculator).computeDiff(any(), any());
    }

    @Test
    void testReconcileChunk_hashMismatch_debitSumsDiffer_creditSumsEqual_shouldRemainNok() {
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        val attachedDebit = okItem("item-attached-debit", BigDecimal.TEN);
        attachedDebit.setTransaction(attachedTx);
        val attachedCredit = creditItem("item-attached-credit", BigDecimal.valueOf(5));
        attachedCredit.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedDebit, attachedCredit));

        // CREDIT sums match (5 == 5) but DEBIT sums differ (10 vs 20) → financially unequal
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER");
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        val detachedDebit = okItem("item-detached-debit", BigDecimal.valueOf(20));
        detachedDebit.setTransaction(detachedTx);
        val detachedCredit = creditItem("item-detached-credit", BigDecimal.valueOf(5));
        detachedCredit.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedDebit, detachedCredit));

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));
        when(erpDiffCalculator.computeDiff(any(), any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
    }

    @Test
    void testReconcileChunk_hashMismatch_creditAndDebitSumsBothEqualAcrossMultipleItems_shouldSetSourceOk() {
        // Financial equality is sum-based, not item-by-item: attachedTx has a single DEBIT item
        // while detachedTx splits the same total across two DEBIT items with different IDs.
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
        attachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        attachedTx.setOrganisation(organisation);
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);
        val attachedDebit = okItem("item-attached-debit", BigDecimal.TEN);
        attachedDebit.setTransaction(attachedTx);
        val attachedCredit = creditItem("item-attached-credit", BigDecimal.valueOf(5));
        attachedCredit.setTransaction(attachedTx);
        attachedTx.setItems(Set.of(attachedDebit, attachedCredit));

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER"); // causes hash mismatch
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        val detachedDebit1 = okItem("item-detached-debit-1", BigDecimal.valueOf(4));
        detachedDebit1.setTransaction(detachedTx);
        val detachedDebit2 = okItem("item-detached-debit-2", BigDecimal.valueOf(6));
        detachedDebit2.setTransaction(detachedTx);
        val detachedCredit = creditItem("item-detached-credit", BigDecimal.valueOf(5));
        detachedCredit.setTransaction(detachedTx);
        detachedTx.setItems(Set.of(detachedDebit1, detachedDebit2, detachedCredit));

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.OK);
        assertThat(reconcilationEntity.getViolations()).noneMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
        verify(erpDiffCalculator, never()).computeDiff(any(), any());
    }
}
