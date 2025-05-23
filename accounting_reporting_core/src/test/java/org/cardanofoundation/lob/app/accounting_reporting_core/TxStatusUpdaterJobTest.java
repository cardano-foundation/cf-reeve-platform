package org.cardanofoundation.lob.app.accounting_reporting_core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.job.TxStatusUpdaterJob;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.LedgerService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionBatchService;

@ExtendWith(MockitoExtension.class)
class TxStatusUpdaterJobTest {

    @Mock
    private LedgerService ledgerService;
    @Mock
    private TransactionBatchService batchService;
    @InjectMocks
    private TxStatusUpdaterJob statusUpdaterJob;

    @Test
    void addToStatusMapTest() throws NoSuchFieldException, IllegalAccessException {
        TxStatusUpdate update = mock(TxStatusUpdate.class);

        statusUpdaterJob.addToStatusUpdateMap(Map.of("123", update));

        Field field = TxStatusUpdaterJob.class.getDeclaredField("txStatusUpdatesMap");
        field.setAccessible(true);
        Map<String, TxStatusUpdate> stringTxStatusUpdateMap = (Map<String, TxStatusUpdate>) field.get(statusUpdaterJob);

        assertNotNull(stringTxStatusUpdateMap);
        assertTrue(stringTxStatusUpdateMap.containsKey("123"));
        assertEquals(update, stringTxStatusUpdateMap.get("123"));
    }

    @Test
    void execute_empty() {
        statusUpdaterJob.execute();

        verifyNoInteractions(batchService, ledgerService);
    }

    @Test
    void execute_exception() throws NoSuchFieldException, IllegalAccessException {
        TxStatusUpdate update = mock(TxStatusUpdate.class);

        statusUpdaterJob.addToStatusUpdateMap(Map.of("123", update));

        doThrow(new RuntimeException()).when(ledgerService).saveAllTransactionEntities(any());

        statusUpdaterJob.execute();

        Field field = TxStatusUpdaterJob.class.getDeclaredField("txStatusUpdatesMap");
        field.setAccessible(true);
        Map<String, TxStatusUpdate> stringTxStatusUpdateMap = (Map<String, TxStatusUpdate>) field.get(statusUpdaterJob);
        // TxStatusUpdate stays within the map
        assertNotNull(stringTxStatusUpdateMap);
        assertTrue(stringTxStatusUpdateMap.containsKey("123"));
        assertEquals(update, stringTxStatusUpdateMap.get("123"));
    }

    @Test
    void execute_success() throws NoSuchFieldException, IllegalAccessException {
        TxStatusUpdate update = mock(TxStatusUpdate.class);
        TransactionEntity entity = mock(TransactionEntity.class);
        statusUpdaterJob.addToStatusUpdateMap(Map.of("123", update));

        when(ledgerService.updateTransactionsWithNewStatuses(Map.of("123", update))).thenReturn(List.of(entity));

        statusUpdaterJob.execute();

        verify(ledgerService).updateTransactionsWithNewStatuses(Map.of("123", update));
        verify(ledgerService).saveAllTransactionEntities(List.of(entity));
        verify(batchService).updateBatchesPerTransactions(Map.of("123", update));
        // TxStatusUpdate must be removed from the internal map
        Field field = TxStatusUpdaterJob.class.getDeclaredField("txStatusUpdatesMap");
        field.setAccessible(true);
        Map<String, TxStatusUpdate> stringTxStatusUpdateMap = (Map<String, TxStatusUpdate>) field.get(statusUpdaterJob);

        assertFalse(stringTxStatusUpdateMap.containsKey("123"));

    }

}
