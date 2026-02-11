package org.cardanofoundation.lob.app.blockchain_publisher.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.ledger.TransactionStatusRequestEvent;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;

@ExtendWith(MockitoExtension.class)
public class BlockchainPublisherServiceTest {

    @Mock
    private TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    @Mock
    private ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    @Mock
    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    @Mock
    private TransactionConverter transactionConverter;
    @Mock
    private ReportConverter reportConverter;

    @InjectMocks
    private BlockchainPublisherService blockchainPublisherService;

    @Test
    void handleTxStatusRequest() {
        TransactionStatusRequestEvent event = new TransactionStatusRequestEvent(Map.of("org1", List.of("tx1")));

        when(transactionEntityRepositoryGateway.findAllById(Set.of("tx1"))).thenReturn(List.of(
                TransactionEntity.builder().organisation(Organisation.builder().id("org1").build()).id("tx1").build()
        ));

        blockchainPublisherService.handleTxStatusRequest(event);

        verify(ledgerUpdatedEventPublisher).sendTxLedgerUpdatedEvents("org1", Set.of(TransactionEntity.builder().organisation(Organisation.builder().id("org1").build()).id("tx1").build()));
    }

}
