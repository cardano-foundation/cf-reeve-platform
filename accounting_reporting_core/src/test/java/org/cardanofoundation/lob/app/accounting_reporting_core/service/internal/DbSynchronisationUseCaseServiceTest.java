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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OrganisationTransactions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.Reconcilation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.reconcilation.ReconcilationCode;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.*;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.LedgerDispatchReceipt;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionProcessingStatus;
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
                .items(Set.of(txItem))
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
                .items(Set.of(txItem2))
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
        assertThat(savedTx.getReconcilation()).isEmpty();
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
}
