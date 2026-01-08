package org.cardanofoundation.lob.app.reporting.job;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.service.ReportingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ReprocessJobTest {

    @Mock
    private ReportingService reportingService;
    @Mock
    private TransactionRepositoryGateway transactionRepositoryGateway;
    @Mock
    private ReportingRepository reportingRepository;

    @InjectMocks
    private ReprocessJob reprocessJob;

    @Test
    void addAllTest() throws NoSuchFieldException, IllegalAccessException {
        TxStatusUpdate txStatusUpdate = mock(TxStatusUpdate.class);

        when(txStatusUpdate.getTxId()).thenReturn("tx123");

        reprocessJob.addAll(Set.of(txStatusUpdate));

        Field field = ReprocessJob.class.getDeclaredField("finalizedTransactionsUpdates");
        field.setAccessible(true);
        Set<String> finalizedTransactionsUpdates = (Set<String>) field.get(reprocessJob);

        assertTrue(finalizedTransactionsUpdates.contains("tx123"));
    }

    @Test
    void executeTest() throws NoSuchFieldException, IllegalAccessException {
        Field field = ReprocessJob.class.getDeclaredField("finalizedTransactionsUpdates");
        field.setAccessible(true);

        field.set(reprocessJob, new HashSet<>(Set.of("tx123", "tx456")));

        TransactionEntity tx1 = mock(TransactionEntity.class);
        TransactionEntity tx2 = mock(TransactionEntity.class);
        when(tx1.getLedgerDispatchStatus()).thenReturn(org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus.FINALIZED);
        when(tx1.getId()).thenReturn("tx123");
        when(tx2.getLedgerDispatchStatus()).thenReturn(LedgerDispatchStatus.COMPLETED);

        when(transactionRepositoryGateway.findByAllId(Set.of("tx123", "tx456"))).thenReturn(List.of(tx1, tx2));

        reprocessJob.execute();

        Set<String> finalizedTransactionsUpdates = (Set<String>) field.get(reprocessJob);
        assertFalse(finalizedTransactionsUpdates.contains("tx123"));
        assertTrue(finalizedTransactionsUpdates.contains("tx456"));

        verify(transactionRepositoryGateway).findByAllId(Set.of("tx123", "tx456"));
        verify(reportingRepository).findAffectedByTxId(List.of("tx123"));
    }

}
