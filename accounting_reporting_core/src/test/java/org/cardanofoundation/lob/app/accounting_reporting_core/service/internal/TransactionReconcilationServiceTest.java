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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.util.ReflectionTestUtils;

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
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
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
    void testReconcileChunk_csvExtractorTypeShouldBypassSourceReconciliation() {
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

        val detachedTx = new TransactionEntity();
        detachedTx.setId("tx1");
        detachedTx.setInternalTransactionNumber("internal1-different");
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

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
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

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of());

        transactionReconcilationService.wrapUpReconcilation(reconcilationId, organisationId, 1L);

        assertThat(reconcilationEntity.getStatus()).isEqualTo(ReconcilationStatus.COMPLETED);
        verify(transactionRepositoryGateway).findAllByDateRange(organisationId, fromDate, toDate);
    }

    // ============== reconcileWithIndexer tests ==============

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

        val tx = createTransactionEntity("tx1", "internal1");
        tx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.NOK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Connection refused");

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(problem));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

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

        val tx = createTransactionEntity("tx1", "internal1");
        tx.setReconcilation(Optional.of(Reconcilation.builder()
                .source(ReconcilationCode.OK)
                .sink(ReconcilationCode.OK)
                .build()));

        when(transactionRepositoryGateway.findAllByDateRange(organisationId, fromDate, toDate))
                .thenReturn(Set.of(tx));

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE)));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

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

        when(indexerReconcilationServiceMock.reconcileWithIndexer(eq(organisationId), eq(fromDate), eq(toDate), anySet()))
                .thenReturn(Either.right(Map.of()));

        transactionReconcilationService.reconcileWithIndexer(reconcilationId, organisationId, fromDate, toDate);

        assertThat(tx.getReconcilation()).isPresent();
        assertThat(tx.getReconcilation().get().getSink()).contains(ReconcilationCode.NOK);
        assertThat(reconcilationEntity.getViolations()).hasSize(1);
        assertThat(reconcilationEntity.getViolations().iterator().next().getRejectionCode())
                .isEqualTo(ReconcilationRejectionCode.SINK_RECONCILATION_FAIL);
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
