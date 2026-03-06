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
import org.javers.core.Changes;
import org.javers.core.Javers;
import org.javers.core.diff.Diff;
import org.javers.core.json.JsonConverter;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.ExtractorType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionType;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
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
        assertThat(missingTx.getViolations().iterator().next().getCode()).isEqualTo(TransactionViolationCode.TX_NOT_IN_ERP);
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
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.VendorPayment);
        attachedTx.setEntryDate(fromDate);

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("DIFFERENT-NUMBER"); // causes hash mismatch
        detachedTx.setExtractorType(ExtractorType.NETSUITE.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        Diff diff = mock(Diff.class);
        Changes changes = mock(Changes.class);
        JsonConverter jsonConverter = mock(JsonConverter.class);
        when(javers.compare(any(), any())).thenReturn(diff);
        when(diff.getChanges()).thenReturn(changes);
        when(javers.getJsonConverter()).thenReturn(jsonConverter);
        when(jsonConverter.toJson(any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL);
        verify(javers).compare(any(), any());
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
        assertThat(missingTx.getReconcilation().get().getSink()).contains(ReconcilationCode.OK);
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
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
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
        attachedTx.setItems(Set.of());
        attachedTx.setTransactionType(TransactionType.Journal);
        attachedTx.setEntryDate(fromDate);

        // detachedTx has a different internal number → hashes differ → source = NOK
        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1-MODIFIED");
        detachedTx.setExtractorType(ExtractorType.CSV.name());
        detachedTx.setOrganisation(organisation);
        detachedTx.setItems(Set.of());
        detachedTx.setTransactionType(TransactionType.Journal);
        detachedTx.setEntryDate(fromDate);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx1")))
                .thenReturn(List.of(attachedTx));
        when(blockchainReaderPublicApi.isOnChain(anySet()))
                .thenReturn(Either.right(Map.of("tx1", true)));

        Diff diff = mock(Diff.class);
        Changes changes = mock(Changes.class);
        JsonConverter jsonConverter = mock(JsonConverter.class);
        when(javers.compare(any(), any())).thenReturn(diff);
        when(diff.getChanges()).thenReturn(changes);
        when(javers.getJsonConverter()).thenReturn(jsonConverter);
        when(jsonConverter.toJson(any())).thenReturn("{}");

        transactionReconcilationService.reconcileChunk(reconcilationId, organisationId, fromDate, toDate, Set.of(detachedTx));

        assertThat(attachedTx.getReconcilation()).isPresent();
        assertThat(attachedTx.getReconcilation().get().getSource()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).anyMatch(
                v -> v.getRejectionCode() == ReconcilationRejectionCode.SOURCE_RECONCILATION_FAIL
        );
    }

    // ============== rollback suffix reconciliation tests ==============

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

        // Detached item uses ERP-style ID: SHA3(txId::0)
        val detachedItem = new TransactionItemEntity();
        detachedItem.setId(TransactionItem.id(txId, "0"));
        detachedItem.setFxRate(BigDecimal.ZERO);
        detachedItem.setAmountFcy(BigDecimal.ZERO);
        detachedItem.setAmountLcy(BigDecimal.ZERO);

        val detachedTx = new TransactionEntity();
        detachedTx.setId(txId);
        detachedTx.setInternalTransactionNumber(txNumber);
        detachedTx.setOrganisation(organisation);
        detachedTx.setTransactionType(TransactionType.VendorPayment);
        detachedTx.setEntryDate(fromDate);
        detachedTx.setItems(new HashSet<>(Set.of(detachedItem)));

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


}
