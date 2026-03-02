package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus.DISPATCHED;
import static org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus.NOT_DISPATCHED;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import java.util.*;

import lombok.val;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OrganisationTransactions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TxRollbackEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;

@ExtendWith(MockitoExtension.class)
class DbSynchronisationUseCaseServiceTest {

    @Mock
    private AccountingCoreTransactionRepository accountingCoreTransactionRepository;

    @Mock
    private TransactionItemRepository transactionItemRepository;

    @Mock
    private TransactionBatchAssocRepository transactionBatchAssocRepository;

    @Mock
    private TransactionBatchService transactionBatchService;

    @InjectMocks
    private DbSynchronisationUseCaseService service;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionConverter transactionConverter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
    }

    @Test
    void shouldDoNothingWithEmptyTransactions() {
        val batchId = "batch1";
        val organisationTransactions = new OrganisationTransactions("org1", Set.of());

        service.execute(batchId, organisationTransactions, 0, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));
        verify(transactionBatchService).updateTransactionBatchStatusAndStats(eq(batchId), eq(0), eq(Optional.empty()));
        verifyNoInteractions(accountingCoreTransactionRepository);
        verifyNoInteractions(transactionItemRepository);
    }

    @Test
    void shouldProcessReprocessFlag() {
        val batchId = "batch1";
        val txId = "tx1";

        val tx1 = new TransactionEntity();
        tx1.setId(txId);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));
        tx1.setInternalTransactionNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.RECONCILATION));

        verify(accountingCoreTransactionRepository).save(eq(tx1));
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    @Test
    void shouldNotUpdateDispatchedTransactions() {
        val batchId = "batch1";
        val txId = "tx1";
        val orgId = "org1";

        val tx1 = new TransactionEntity();
        tx1.setId(txId);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));
        tx1.setInternalTransactionNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);
        tx1.setOrganisation(Organisation
                .builder()
                .id(orgId)
                .name("organisation 1")
                .countryCode("CHF")
                .currencyId("ISO_4217:CHF")
                .build()
        );

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions(orgId, txs);

        when(accountingCoreTransactionRepository.findAllById(eq(Set.of(txId)))).thenReturn(List.of(tx1));

        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository, never()).save(any());
        verify(transactionItemRepository, never()).save(any());
    }

    @Test
    void shouldStoreNewTransactions() {
        val batchId = "batch1";
        val tx1Id = "3112ec27094335dd858948b3086817d7e290586d235c529be21f03ba5d583503";
        val orgId = "org1";

        val txItem1 = new TransactionItemEntity();
        txItem1.setId(TransactionItem.id(tx1Id, "0"));
        val txItem2 = new TransactionItemEntity();
        txItem2.setId(TransactionItem.id(tx1Id, "1"));

        val items = new LinkedHashSet<TransactionItemEntity>();
        items.add(txItem1);

        val tx1 = new TransactionEntity();
        tx1.setId(tx1Id);
        tx1.setItems(items);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions(orgId, txs);

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));

        service.execute(batchId, transactions, txs.size(), new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository).save(eq(tx1));
        verify(transactionItemRepository).saveAll(eq(items));
    }

    @Test
    void shouldHandleMixedTransactions() {
        val tx1Id = "3112ec27094335dd858948b3086817d7e290586d235c529be21f03ba5d583503";
        val tx2Id = "44f7f0e32ca04ad46b1d6a0a1dbf14a6aac6f5fb96067725de5f0345d3619afe";
        val orgId = "org1";
        val batchId = "batch1";

        val dispatchedTx = new TransactionEntity();
        dispatchedTx.setId(tx1Id);
        dispatchedTx.setAccountingPeriod(YearMonth.of(2021, 1));
        dispatchedTx.setOrganisation(Organisation
                .builder()
                .id(orgId)
                .name("organisation 1")
                .countryCode("ISO_4217:CHF")
                .build()
        );
        dispatchedTx.setTransactionApproved(true);
        dispatchedTx.setLedgerDispatchApproved(true);
        dispatchedTx.setLedgerDispatchStatus(DISPATCHED);

        val notDispatchedTx = new TransactionEntity();
        notDispatchedTx.setOrganisation(Organisation
                .builder()
                .id(orgId)
                .name("organisation 1")
                .countryCode("ISO_4217:CHF")
                .build()
        );
        notDispatchedTx.setId(tx2Id);
        notDispatchedTx.setAccountingPeriod(YearMonth.of(2021, 1));
        dispatchedTx.setTransactionApproved(true);
        notDispatchedTx.setLedgerDispatchApproved(false);
        notDispatchedTx.setLedgerDispatchStatus(NOT_DISPATCHED);

        val txs = Set.of(dispatchedTx, notDispatchedTx);
        val mixedTransactions = new OrganisationTransactions(orgId, txs);
        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(dispatchedTx));

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));

        service.execute(batchId, mixedTransactions, 2, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository, never()).save(dispatchedTx);
        verify(accountingCoreTransactionRepository).save(notDispatchedTx);
        verify(transactionItemRepository).saveAll(any());
    }

    @Test
    void shouldReprocessFlagTest() {
        val batchId = "batch1";
        val txId = "tx1";

        val tx1 = Mockito.mock(TransactionEntity.class);
        tx1.setId(txId);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));
        tx1.setInternalTransactionNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.REPROCESSING));

        verify(accountingCoreTransactionRepository).save(eq(tx1));
        verify(tx1, times(1)).clearAllItemsRejectionsSource(Source.LOB);
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    @Test
    void shouldRollbackTransactionWhenRollbackEnabled() {
        // Given
        val txId = "rollback-tx-123";
        val orgId = "org1";
        val batchId = "batch-rollback";

        // Create a transaction that needs to be rolled back
        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val txItem2 = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "2"));

        // Create the transaction with proper status
        val tx = TransactionEntity.builder()
                .id(txId)
                .items(new HashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(LedgerDispatchStatus.DISPATCHED)
                .reconcilation(Reconcilation.builder()
                        .source(ReconcilationCode.NOK)
                        .sink(ReconcilationCode.NOK)
                        .finalStatus(ReconcilationCode.NOK)
                        .build())
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        val txModified = TransactionEntity.builder()
                .id(txId)
                .items(new HashSet<>(Set.of(txItem2)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(LedgerDispatchStatus.DISPATCHED)
                .reconcilation(Reconcilation.builder()
                        .source(ReconcilationCode.NOK)
                        .sink(ReconcilationCode.NOK)
                        .finalStatus(ReconcilationCode.NOK)
                        .build())
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any()))
                .thenReturn(List.of(tx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(service, "rollbackEnabled", Optional.of(true));

        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(txModified)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        ArgumentCaptor<TransactionEntity> savedTxCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(accountingCoreTransactionRepository).save(savedTxCaptor.capture());

        TransactionEntity savedTx = savedTxCaptor.getValue();
        assertThat(savedTx.getLedgerDispatchApproved()).isFalse();
        assertThat(savedTx.getTransactionApproved()).isFalse();
        assertThat(savedTx.getLedgerDispatchReceipt()).isEmpty();
        assertThat(savedTx.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
        assertThat(savedTx.getReconcilation()).isPresent();
    }

    @Test
    void shouldExtractionFlagTest() {
        val batchId = "batch1";
        val txId = "tx1";

        val tx1 = Mockito.mock(TransactionEntity.class);
        tx1.setId(txId);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));
        tx1.setInternalTransactionNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository).save(eq(tx1));
        verify(tx1, times(1)).clearAllItemsRejectionsSource(Source.ERP);
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    @Test
    void shouldRaiseViolationForDispatchedTransactionWhenModified() {
        // Given: a dispatched transaction in DB and an incoming modified version
        val batchId = "batch1";
        val txId = "tx-violation-test";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        // Existing dispatched transaction in DB
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-existing")
                .transactionApproved(true)
                .ledgerDispatchApproved(true)
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        // Incoming modified transaction (different internal number to simulate change)
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 2))  // Different period
                .internalTransactionNumber("txn-modified")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: the transaction should NOT be saved (it's dispatched) but a violation should be raised
        verify(accountingCoreTransactionRepository, never()).save(any());
        assertThat(incomingTx.getViolations().stream()
                .anyMatch(v -> v.getCode() == TransactionViolationCode.TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE)).isTrue();
    }

    @Test
    void shouldPublishTxRollbackEventWhenRollbackProcessing() {
        // Given
        val txId = "rollback-event-tx";
        val orgId = "org1";
        val batchId = "batch-event";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        // Existing dispatched transaction
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-old")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.DISPATCHED)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        // Incoming transaction marked for ROLLBACK (different internalTransactionNumber to be considered changed)
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-new")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(service, "rollbackEnabled", Optional.of(true));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: TxRollbackEvent should be published
        ArgumentCaptor<TxRollbackEvent> eventCaptor = ArgumentCaptor.forClass(TxRollbackEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTransactionId()).isEqualTo(txId);
    }

    @Test
    void shouldNotResetTransactionWhenRollbackDisabled() {
        // Given
        val txId = "rollback-disabled-tx";
        val orgId = "org1";
        val batchId = "batch-disabled";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        // Existing dispatched transaction
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-old")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.DISPATCHED)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(DISPATCHED)
                .reconcilation(Reconcilation.builder()
                        .source(ReconcilationCode.OK)
                        .sink(ReconcilationCode.OK)
                        .finalStatus(ReconcilationCode.OK)
                        .build())
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        // Incoming transaction marked for ROLLBACK (different internalTransactionNumber to be considered changed)
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-new")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Rollback is disabled
        ReflectionTestUtils.setField(service, "rollbackEnabled", Optional.of(false));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: Transaction should be saved but dispatch status should remain unchanged
        ArgumentCaptor<TransactionEntity> savedTxCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(accountingCoreTransactionRepository).save(savedTxCaptor.capture());

        TransactionEntity savedTx = savedTxCaptor.getValue();
        // When rollback is disabled, the dispatch-related fields should NOT be reset
        assertThat(savedTx.getLedgerDispatchApproved()).isTrue();
        assertThat(savedTx.getTransactionApproved()).isTrue();
        assertThat(savedTx.getLedgerDispatchStatus()).isEqualTo(DISPATCHED);
    }

    @Test
    void shouldUpdateRelatedBatchesWhenTransactionModified() {
        // Given
        val batchId = "batch-new";
        val existingBatchId = "batch-existing";
        val txId = "tx-batch-update";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val existingBatch = new TransactionBatchEntity();
        existingBatch.setId(existingBatchId);

        // Existing NOT dispatched transaction in DB with a batch
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-old")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .batches(new LinkedHashSet<>(Set.of(existingBatch)))
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        // Incoming modified transaction (different internalTransactionNumber to be considered changed)
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-new")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 2))
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: Both new batch and existing batch should be updated
        verify(transactionBatchService).updateTransactionBatchStatusAndStats(eq(batchId), anyInt(), eq(Optional.empty()));
        verify(transactionBatchService).updateTransactionBatchStatusAndStats(eq(existingBatchId), eq(null), eq(Optional.empty()));
    }

    @Test
    void shouldProcessReconcilationTrigger() {
        val batchId = "batch-reconcilation";
        val txId = "tx-reconcilation";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx1 = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions(orgId, txs);

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));

        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.RECONCILATION));

        // Verify transaction is saved (RECONCILATION goes through processTransactionsForTheFirstTime)
        verify(accountingCoreTransactionRepository).save(eq(tx1));
        // Verify batch associations are processed
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    @Test
    void shouldNotTriggerRollbackWhenTransactionIsNotChanged() {
        // Given: a ROLLBACK transaction that is identical to the DB copy (isChanged = false)
        val txId = "rollback-unchanged-tx";
        val orgId = "org1";
        val batchId = "batch-unchanged";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val org = Organisation.builder()
                .id(orgId)
                .name("Test Org")
                .countryCode("CH")
                .currencyId("ISO_4217:CHF")
                .build();

        // Existing dispatched transaction in DB
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-same")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.DISPATCHED)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(org)
                .build();

        // Incoming ROLLBACK transaction - identical fields so isChanged = false
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-same")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(org)
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));

        ReflectionTestUtils.setField(service, "rollbackEnabled", Optional.of(true));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: No rollback event should be published since isChanged is false
        verify(eventPublisher, never()).publishEvent(any(TxRollbackEvent.class));
        // Transaction should NOT be saved (isChanged=false, so it goes to alreadyStoredCount)
        verify(accountingCoreTransactionRepository, never()).save(any());
    }

    @Test
    void shouldTriggerRollbackWhenTransactionIsChanged() {
        // Given: a ROLLBACK transaction that is different from the DB copy (isChanged = true)
        val txId = "rollback-changed-tx";
        val orgId = "org1";
        val batchId = "batch-changed";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val org = Organisation.builder()
                .id(orgId)
                .name("Test Org")
                .countryCode("CH")
                .currencyId("ISO_4217:CHF")
                .build();

        // Existing dispatched transaction in DB
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-old")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.DISPATCHED)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchReceipt(new LedgerDispatchReceipt("receipt-123", "success"))
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(org)
                .build();

        // Incoming ROLLBACK transaction - different internalTransactionNumber so isChanged = true
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .internalTransactionNumber("txn-new")
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .processingStatus(TransactionProcessingStatus.ROLLBACK)
                .ledgerDispatchApproved(true)
                .transactionApproved(true)
                .ledgerDispatchStatus(DISPATCHED)
                .organisation(org)
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(service, "rollbackEnabled", Optional.of(true));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: Rollback event SHOULD be published since isChanged is true
        ArgumentCaptor<TxRollbackEvent> eventCaptor = ArgumentCaptor.forClass(TxRollbackEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTransactionId()).isEqualTo(txId);

        // Transaction should be saved with rollback fields reset
        ArgumentCaptor<TransactionEntity> savedTxCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(accountingCoreTransactionRepository).save(savedTxCaptor.capture());
        TransactionEntity savedTx = savedTxCaptor.getValue();
        assertThat(savedTx.getLedgerDispatchApproved()).isFalse();
        assertThat(savedTx.getTransactionApproved()).isFalse();
        assertThat(savedTx.getLedgerDispatchReceipt()).isEmpty();
        assertThat(savedTx.getLedgerDispatchStatus()).isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
    }

    @Test
    void shouldHandleTransactionWithExistingBatchAssociation() {
        // Given
        val batchId = "batch1";
        val txId = "tx-existing-assoc";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val tx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        val existingAssoc = new TransactionBatchAssocEntity(
                new TransactionBatchAssocEntity.Id(batchId, txId));

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of());
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionBatchAssocRepository.findById(any())).thenReturn(Optional.of(existingAssoc));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(tx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: existing association should be reused
        verify(transactionBatchAssocRepository).findById(any());
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    // ============== NEW: ERP violation tests (LOB-1332) ==============

    @Test
    void shouldConsiderTransactionChangedWhenExistingHasErpViolation() {
        // Given: existing TX in DB with an ERP violation and SAME data as incoming
        val batchId = "batch-erp-violation";
        val txId = "3112ec27094335dd858948b3086817d7e290586d235c529be21f03ba5d583503";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val erpViolation = TransactionViolation.builder()
                .code(TransactionViolationCode.TX_NOT_IN_ERP)
                .severity(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("reconciliation")
                .txItemId(txId)
                .build();

        // Existing TX has an ERP violation — even if hashes match, it must be treated as changed
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-same")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .violations(new LinkedHashSet<>(Set.of(erpViolation)))
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        // Incoming TX is identical (same hash) — but because of ERP violation, should be processed
        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-same")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: transaction IS saved (because existing has ERP violation → always "changed")
        verify(accountingCoreTransactionRepository).save(any(TransactionEntity.class));
    }

    @Test
    void shouldClearErpViolationsWhenUpdatingExistingTransaction() {
        // Given: existing TX with an ERP violation, incoming TX has different data
        val batchId = "batch-clear-erp";
        val txId = "44f7f0e32ca04ad46b1d6a0a1dbf14a6aac6f5fb96067725de5f0345d3619afe";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val erpViolation = TransactionViolation.builder()
                .code(TransactionViolationCode.TX_NOT_IN_ERP)
                .severity(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("reconciliation")
                .txItemId(txId)
                .build();

        val existingTx = Mockito.spy(TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-old")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .violations(new LinkedHashSet<>(Set.of(erpViolation)))
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build());

        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 2)) // Different period → hash mismatch
                .internalTransactionNumber("txn-new")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: ERP violations are cleared from the attached entity before re-import
        verify(existingTx).clearAllViolations(Source.ERP);
    }

    @Test
    void shouldNotSaveWhenErpViolatedTxIsDispatchApproved() {
        // Given: existing TX with an ERP violation AND both approvals set (dispatch-marked)
        val batchId = "batch-erp-dispatch-approved";
        val txId = "44f7f0e32ca04ad46b1d6a0a1dbf14a6aac6f5fb96067725de5f0345d3619af0";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val erpViolation = TransactionViolation.builder()
                .code(TransactionViolationCode.TX_NOT_IN_ERP)
                .severity(org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.ERROR)
                .source(Source.ERP)
                .processorModule("reconciliation")
                .txItemId(txId)
                .build();

        // Existing TX: ERP violation + both approvals → allApprovalsPassedForTransactionDispatch() = true
        val existingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-approved")
                .transactionApproved(true)
                .ledgerDispatchApproved(true)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .violations(new LinkedHashSet<>(Set.of(erpViolation)))
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-approved")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: isChanged=true (ERP violation forces re-check) but isDispatchMarked=true → NOT saved
        verify(accountingCoreTransactionRepository, never()).save(any(TransactionEntity.class));
    }

    @Test
    void shouldCallClearErpViolationsEvenWithoutPreviousErpViolations() {
        // Given: existing TX with NO ERP violations but a hash mismatch (different accountingPeriod)
        val batchId = "batch-no-erp";
        val txId = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
        val orgId = "org1";

        val txItem = new TransactionItemEntity();
        txItem.setId(TransactionItem.id(txId, "0"));

        val existingTx = Mockito.spy(TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 1))
                .internalTransactionNumber("txn-old")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build());

        val incomingTx = TransactionEntity.builder()
                .id(txId)
                .items(new LinkedHashSet<>(Set.of(txItem)))
                .accountingPeriod(YearMonth.of(2023, 2)) // Different period → hash mismatch → isChanged
                .internalTransactionNumber("txn-new")
                .transactionApproved(false)
                .ledgerDispatchApproved(false)
                .ledgerDispatchStatus(NOT_DISPATCHED)
                .organisation(Organisation.builder()
                        .id(orgId)
                        .name("Test Org")
                        .countryCode("CH")
                        .currencyId("ISO_4217:CHF")
                        .build())
                .build();

        when(accountingCoreTransactionRepository.findAllById(any())).thenReturn(List.of(existingTx));
        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        service.execute(batchId, new OrganisationTransactions(orgId, Set.of(incomingTx)), 1,
                new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        // Then: clearAllViolations(ERP) is always called when updating an existing TX, even without prior ERP violations
        verify(existingTx).clearAllViolations(Source.ERP);
        verify(accountingCoreTransactionRepository).save(any(TransactionEntity.class));
    }
}
