package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.DISPATCHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.LedgerDispatchStatus.NOT_DISPATCHED;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode.TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity.WARN;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.val;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OrganisationTransactions;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionItem;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchAssocEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionViolation;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.business_rules.ProcessorFlags;

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
    @Mock
    private TransactionConverter transactionCoverter;

    @InjectMocks
    private DbSynchronisationUseCaseService service;

    @Test
    void shouldDoNothingWithEmptyTransactions() {
        val batchId = "batch1";
        val organisationTransactions = new OrganisationTransactions("org1", Set.of());

        service.execute(batchId, organisationTransactions, 0, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));
        verify(transactionBatchService).updateTransactionBatchStatusAndStats(batchId, Optional.of(0));
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
        tx1.setTransactionInternalNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.RECONCILATION));

        verify(accountingCoreTransactionRepository).save(tx1);
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
        tx1.setTransactionInternalNumber("txn123");
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

        when(accountingCoreTransactionRepository.findAllById(Set.of(txId))).thenReturn(List.of(tx1));

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

        verify(accountingCoreTransactionRepository).save(tx1);
        verify(transactionItemRepository).saveAll(items);
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
        tx1.setTransactionInternalNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.REPROCESSING));

        verify(accountingCoreTransactionRepository).save(tx1);
        verify(tx1, times(1)).clearAllItemsRejectionsSource(Source.LOB);
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    void shouldExtractionFlagTest() {
        val batchId = "batch1";
        val txId = "tx1";

        val tx1 = Mockito.mock(TransactionEntity.class);
        tx1.setId(txId);
        tx1.setAccountingPeriod(YearMonth.of(2021, 1));
        tx1.setTransactionInternalNumber("txn123");
        tx1.setTransactionApproved(true);
        tx1.setLedgerDispatchApproved(true);
        tx1.setLedgerDispatchStatus(DISPATCHED);

        val txs = Set.of(tx1);
        val transactions = new OrganisationTransactions("org1", txs);

        when(accountingCoreTransactionRepository.save(any(TransactionEntity.class))).thenAnswer((Answer<TransactionEntity>) invocation -> (TransactionEntity) invocation.getArgument(0));
        service.execute(batchId, transactions, 1, new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository).save(tx1);
        verify(tx1, times(1)).clearAllItemsRejectionsSource(Source.ERP);
        verify(transactionBatchAssocRepository).saveAll(any(Set.class));
    }

    @Test
    void processTransactionsForTheFirstTimeTest_emptyTxListNoInteraction(){


        service.processTransactionsForTheFirstTime("batchId", Set.of(), Optional.of(0), new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

        verify(accountingCoreTransactionRepository).findAllById(Collections.emptySet());
        verify(accountingCoreTransactionRepository).saveAll(Collections.emptySet());
        verify(transactionBatchAssocRepository).saveAll(Collections.emptySet());
        verify(transactionBatchService).updateTransactionBatchStatusAndStats("batchId", Optional.of(0));
        verifyNoMoreInteractions(accountingCoreTransactionRepository);
        verifyNoMoreInteractions(transactionBatchAssocRepository);
        verifyNoMoreInteractions(transactionBatchService);
        verifyNoInteractions(transactionItemRepository);
        verifyNoInteractions(transactionCoverter);
    }

    @Test
    void processTransactionsForTheFirstTimeTest_dispatchedAndChanged() {
        try (MockedStatic<ERPSourceTransactionVersionCalculator> mockedStatic = mockStatic(ERPSourceTransactionVersionCalculator.class)) {
            TransactionEntity tx1 = Mockito.mock(TransactionEntity.class);
            TransactionEntity tx2 = Mockito.mock(TransactionEntity.class);
            when(tx1.getId()).thenReturn("txId");
            when(tx2.getId()).thenReturn("txId");
            when(tx2.getTransactionInternalNumber()).thenReturn("internalTx2Number");
            when(tx2.allApprovalsPassedForTransactionDispatch()).thenReturn(true);
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx1))
                    .thenReturn("tx1Id");
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx2))
                    .thenReturn("tx2Id");
            when(accountingCoreTransactionRepository.findAllById(Set.of("txId"))).thenReturn(List.of(tx2));

            service.processTransactionsForTheFirstTime("batchId", Set.of(tx1), Optional.of(1), new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));
            TransactionViolation v = TransactionViolation.builder()
                    .code(TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE)
                    .severity(WARN)
                    .source(Source.ERP)
                    .processorModule("DbSynchronisationUseCaseService")
                    .bag(
                            Map.of(
                                    "transactionNumber", "internalTx2Number"
                            )
                    )
                    .build();
            verify(tx2).addViolation(v);

            verify(accountingCoreTransactionRepository).saveAll(Set.of(tx2));
            verify(transactionBatchAssocRepository).findById(new TransactionBatchAssocEntity.Id("batchId", "txId"));
            verify(transactionBatchAssocRepository).saveAll(Set.of(new TransactionBatchAssocEntity(new TransactionBatchAssocEntity.Id("batchId", "txId"))));
            verify(transactionBatchService).updateTransactionBatchStatusAndStats("batchId", Optional.of(1));
            verifyNoMoreInteractions(accountingCoreTransactionRepository);
            verifyNoMoreInteractions(transactionBatchAssocRepository);
            verifyNoMoreInteractions(transactionBatchService);
            verifyNoInteractions(transactionItemRepository);
        }
    }

    @Test
    void processTransactionsForTheFirstTimeTest_dispatchedAndNotChanged() {
        try(MockedStatic<ERPSourceTransactionVersionCalculator> mockedStatic = mockStatic(ERPSourceTransactionVersionCalculator.class)) {
            TransactionEntity tx1 = Mockito.mock(TransactionEntity.class);
            TransactionEntity tx2 = Mockito.mock(TransactionEntity.class);
            when(tx1.getId()).thenReturn("txId");
            when(tx2.getId()).thenReturn("txId");
            when(tx2.getTransactionInternalNumber()).thenReturn("internalTx2Number");
            when(tx2.allApprovalsPassedForTransactionDispatch()).thenReturn(true);
            LinkedHashSet<TransactionViolation> violations = new LinkedHashSet<>();
            violations.add(TransactionViolation.builder()
                    .code(TX_VERSION_CONFLICT_TX_NOT_MODIFIABLE)
                    .severity(WARN)
                    .source(Source.ERP)
                    .processorModule("DbSynchronisationUseCaseService")
                    .bag(
                            Map.of(
                                    "transactionNumber", "internalTx2Number"
                            )
                    )
                    .build());
            when(tx2.getViolations()).thenReturn(violations);
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx1))
                    .thenReturn("tx1Id");
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx2))
                    .thenReturn("tx1Id");
            when(accountingCoreTransactionRepository.findAllById(Set.of("txId"))).thenReturn(List.of(tx2));

            service.processTransactionsForTheFirstTime("batchId", Set.of(tx1), Optional.of(1), new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

            verify(tx2).setViolations(Collections.emptySet());
            verify(tx2).recalcValidationStatus();
            verify(accountingCoreTransactionRepository).saveAll(Set.of(tx2));
            verify(transactionBatchAssocRepository).findById(new TransactionBatchAssocEntity.Id("batchId","txId"));
            verify(transactionBatchAssocRepository).saveAll(Set.of(new TransactionBatchAssocEntity(new TransactionBatchAssocEntity.Id("batchId","txId"))));
            verify(transactionBatchService).updateTransactionBatchStatusAndStats("batchId", Optional.of(1));
            verifyNoMoreInteractions(accountingCoreTransactionRepository);
            verifyNoMoreInteractions(transactionBatchAssocRepository);
            verifyNoMoreInteractions(transactionBatchService);
            verifyNoInteractions(transactionItemRepository);
        }
    }

    @Test
    void processTransactionsForTheFirstTimeTest_NotDispatched() {
        try(MockedStatic<ERPSourceTransactionVersionCalculator> mockedStatic = mockStatic(ERPSourceTransactionVersionCalculator.class)) {
            TransactionEntity tx1 = Mockito.mock(TransactionEntity.class);
            TransactionEntity tx2 = Mockito.mock(TransactionEntity.class);
            when(tx1.getId()).thenReturn("txId");
            when(tx2.getId()).thenReturn("txId");
            when(tx2.allApprovalsPassedForTransactionDispatch()).thenReturn(false);
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx1))
                    .thenReturn("tx1Id");
            mockedStatic.when(() -> ERPSourceTransactionVersionCalculator.compute(tx2))
                    .thenReturn("tx2Id");
            when(accountingCoreTransactionRepository.findAllById(Set.of("txId"))).thenReturn(List.of(tx2));
            when(accountingCoreTransactionRepository.save(tx2)).thenReturn(tx2);

            service.processTransactionsForTheFirstTime("batchId", Set.of(tx1), Optional.of(1), new ProcessorFlags(ProcessorFlags.Trigger.IMPORT));

            verify(transactionCoverter).copyFields(tx2, tx1);
            verify(accountingCoreTransactionRepository).save(tx2);

            verifyNoMoreInteractions(transactionCoverter);
        }
    }
}
