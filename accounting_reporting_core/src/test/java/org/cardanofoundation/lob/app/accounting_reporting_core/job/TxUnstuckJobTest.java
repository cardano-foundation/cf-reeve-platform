package org.cardanofoundation.lob.app.accounting_reporting_core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Organisation;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.AccountingCoreTransactionRepository;

@ExtendWith(MockitoExtension.class)
public class TxUnstuckJobTest {

    @Mock
    private AccountingCoreTransactionRepository accountingCoreTransactionRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private TxUnstuckJob txUnstuckJob;

    @BeforeEach
    void setup() throws NoSuchFieldException, IllegalAccessException {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        txUnstuckJob = new TxUnstuckJob(accountingCoreTransactionRepository, applicationEventPublisher, clock);
        Field field = TxUnstuckJob.class.getDeclaredField("stuckDelay");
        field.setAccessible(true);
        field.set(txUnstuckJob, "PT10M");
    }

    @Test
    void testExecute_NoStuckTransactions() {
        when(accountingCoreTransactionRepository.findStuckTransactions(any())).thenReturn(List.of());

        txUnstuckJob.execute();

        verify(accountingCoreTransactionRepository).findStuckTransactions(any());
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void testExecute_WithStuckTransactions() {
        when(accountingCoreTransactionRepository.findStuckTransactions(any())).thenReturn(List.of(
                TransactionEntity.builder().organisation(Organisation.builder().id("org1").build()).id("tx1").build(),
                TransactionEntity.builder().id("tx2").organisation(Organisation.builder().id("org2").build()).build()
        ));

        txUnstuckJob.execute();

        TransactionStatusRequestEvent event = new TransactionStatusRequestEvent(Map.of("org1", List.of("tx1"),"org2", List.of("tx2")));

        verify(accountingCoreTransactionRepository).findStuckTransactions(any());
        ArgumentCaptor<TransactionStatusRequestEvent> captor = ArgumentCaptor.forClass(TransactionStatusRequestEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        TransactionStatusRequestEvent value = captor.getValue();
        assertEquals(event.getOrganisationTransactionIdMap(), value.getOrganisationTransactionIdMap());
    }

}
