package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.FatalError;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.Transactions;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.core.TxLine;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteIngestionEntity;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetsuiteIngestionBody;
import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository.IngestionRepository;

@ExtendWith(MockitoExtension.class)
class NetSuiteExtractionServiceTest {

    private final String ingestionBody = "1f8b08000000000000ffed55c18ed33010bdef57583937959d2d42f4062b16b442805838210e537bd25a9bd8d1d8e9365af5df99a4dd264d5b71022144955aedcc7bf6ccf33ce5e94a88a4f484c95ce450049cb481c23a0c1cf9ce7f8478ea560e877a11acb1408df32b8b04a4570dc3d4e419119baadd29b9f33539289243c240444dc8ab69f399cc5ea4324b3329d4cb393f52f6d802422cbdb1b945d3f27e4988046e009ca52a4baf959072de3d3d10dd61c36376001dad77ae2e17485d039fbe7df9f8fa43c69f41612c0b27e511d5760dddbd4da594aac7aef92c4f5374d1c6668739496a5f56e01a07e5a8248315502c997c2cf400a259a570311b61a3bd19ed0a5afbdac569dfe54c49a5f83bd4e880da57f5d54728c43bf22108ebb8641439f952042850f85c2cbd3741f0058880b4b61a83988bfb7d7210fa4c7e6d038b1c44ee49205009f48046e475ac09056e2a7481c76e5c4909d68d7aaf89d0e9aee7fb47cb85ddf245e81e70b3074c43532e7cd1e26ededf0ee660a357e0962cdd6e18d474a880c1858df906caf6f4d1c984e638a7e429f732f398d767cbba88b6b7570b18941b22c43a10e66d1caa8a95641b9ddc9875115bd7eda64d5dcfb2a4836c27ff6d7cc6c6eadfb271f6676cfca6b68511556fe654f0783e58b714848f40e66f34f0c8a4a72ebe68e0b3cc73bcdfe1e0997ab57730af3fba37f31a8a1a5758540cd8193a697fb580edd5f62767da246cce070000";

    @Mock
    private IngestionRepository ingestionRepository;
    @Mock
    private NetSuiteClient netSuiteClient;
    @Mock
    private TransactionConverter transactionConverter;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private SystemExtractionParametersFactory systemExtractionParametersFactory;
    @Mock
    private ExtractionParametersFilteringService extractionParametersFilteringService;
    @Mock
    private NetSuiteParser netSuiteParser;

    private NetSuiteExtractionService netSuiteExtractionService;

    @BeforeEach
    void setUp() {
        netSuiteExtractionService = new NetSuiteExtractionService(ingestionRepository, netSuiteClient, transactionConverter, applicationEventPublisher, systemExtractionParametersFactory, extractionParametersFilteringService, netSuiteParser, 1, "",true );
    }

    @Test
    void testStartNewERPExtraction_errorJson() {
        when(netSuiteClient.retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class))).thenReturn(Either.left(Problem.builder()
                .withStatus(Status.BAD_REQUEST)
                .withTitle("testTitle")
                .withDetail("testDetail")
                .build()));
        when(netSuiteClient.getBaseUrl()).thenReturn("testBaseUrl");

        netSuiteExtractionService.startNewERPExtraction("orgId", "userId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build());

        // verifying that the event was published
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(netSuiteClient).retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class));
        verify(netSuiteClient).getBaseUrl();
        verifyNoMoreInteractions(applicationEventPublisher, netSuiteClient);

    }

    @Test
    void testStartNewERPExtraction_emptyBody() {
        when(netSuiteClient.retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class))).thenReturn(Either.right(Optional.empty()));
        netSuiteExtractionService.startNewERPExtraction("orgId", "userId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build());

        // verifying that the event was published
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(netSuiteClient).retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class));
        verifyNoMoreInteractions(applicationEventPublisher, netSuiteClient);
    }

    @Test
    void testStartNewERPExtraction_errorCreatingExtractionParams() {
        when(netSuiteClient.retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class))).thenReturn(Either.right(Optional.of(List.of("TestBody"))));
        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.left(Problem.builder()
                .withStatus(Status.BAD_REQUEST)
                .withTitle("testTitle")
                .withDetail("testDetail")
                .build()));

        netSuiteExtractionService.startNewERPExtraction("orgId", "userId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(netSuiteClient).retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class));
        verify(systemExtractionParametersFactory).createSystemExtractionParameters("orgId");
        verifyNoMoreInteractions(applicationEventPublisher, netSuiteClient, systemExtractionParametersFactory);
    }

    @Test
    @Disabled("Disabled until we have a proper test for the database")
    void testStartNewERPExtraction_successfull() {
        when(netSuiteClient.retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class))).thenReturn(Either.right(Optional.of(List.of("TestBody"))));
        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.right(SystemExtractionParameters.builder().build()));
        when(ingestionRepository.saveAndFlush(any())).thenReturn(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, "ingestionBody", "id", "ingestionBodyDebug", "ingestionChecksum"))));
        when(netSuiteParser.saveToDataBase("id", Optional.of(List.of("TestBody")), true)).thenReturn(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, "ingestionBody", "id", "ingestionBodyDebug", "ingestionChecksum"))));
        netSuiteExtractionService.startNewERPExtraction("orgId", "userId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build());

        verify(netSuiteClient).retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class));
        verify(systemExtractionParametersFactory).createSystemExtractionParameters("orgId");
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchStartedEvent.class));
        verify(ingestionRepository).saveAndFlush(any());
        verifyNoMoreInteractions(netSuiteClient, systemExtractionParametersFactory, applicationEventPublisher, ingestionRepository);
    }

    @Test
    void testStartNewERPExtraction_exceptionHandling() {
        when(netSuiteClient.retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class))).thenThrow(new RuntimeException("TestException"));

        netSuiteExtractionService.startNewERPExtraction("orgId", "userId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(netSuiteClient).retrieveLatestNetsuiteTransactionLines(any(LocalDate.class), any(LocalDate.class));
        verifyNoMoreInteractions(applicationEventPublisher, netSuiteClient);
    }

    @Test
    void testContinueERPExtraction_NotFound() {
        when(ingestionRepository.findById("id")).thenReturn(Optional.empty());
        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(ingestionRepository).findById("id");
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository);
    }

    @Test
    void testContinueERPExtraction_orgIdMismatch() {
        when(ingestionRepository.findById("id")).thenReturn(Optional.of(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, "ingestionBody", "id", "ingestionBodyDebug", "ingestionChecksum")))));
        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().organisationId("org1").from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().organisationId("orgId2").build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(ingestionRepository).findById("id");
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository);
    }

    @Test
    void testContinueERPExtraction_parseSearchFailed() {
        when(ingestionRepository.findById("id")).thenReturn(Optional.of(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, ingestionBody, "id", "ingestionBodyDebug", "ingestionChecksum")))));
        when(netSuiteParser.getAllTxLinesFromBodies(any())).thenReturn(Either.left(Problem.builder()
                .withStatus(Status.BAD_REQUEST)
                .withTitle("testTitle")
                .withDetail("testDetail")
                .build()));

        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().organisationId("org").from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().organisationId("org").build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(ingestionRepository).findById("id");
        verify(netSuiteParser).getAllTxLinesFromBodies(any());
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository, netSuiteParser);
    }

    @Test
    void testContinueERPExtraction_transactionConvertFailed() {
        when(ingestionRepository.findById("id")).thenReturn(Optional.of(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, ingestionBody, "id", "ingestionBodyDebug", "ingestionChecksum")))));
        when(netSuiteParser.getAllTxLinesFromBodies(any())).thenReturn(Either.right(List.of()));
        when(transactionConverter.convert("org", "id", List.of())).thenReturn(Either.left(new FatalError(FatalError.Code.ADAPTER_ERROR, "test", Map.of())));

        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().organisationId("org").from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().organisationId("org").build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(ingestionRepository).findById("id");
        verify(netSuiteParser).getAllTxLinesFromBodies(any());
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository, netSuiteParser);
    }

    @Test
    void testContinueERPExtraction_transactionConvertSuccess() {
        TxLine mockTxLine = mock(TxLine.class);
        when(ingestionRepository.findById("id")).thenReturn(Optional.of(new NetSuiteIngestionEntity("id", "adapterInstanceId", List.of(new NetsuiteIngestionBody(1L, ingestionBody, "id", "ingestionBodyDebug", "ingestionChecksum")))));
        when(transactionConverter.convert("orgId", "id", List.of(mockTxLine))).thenReturn(Either.right(new Transactions("org", Set.of())));
        when(extractionParametersFilteringService.applyExtractionParameters(any(), any(), any())).thenReturn(Set.of(Transaction.builder().id("id1").build(), Transaction.builder().id("id2").build(), Transaction.builder().id("id3").build()));
        when(netSuiteParser.getAllTxLinesFromBodies(any())).thenReturn(Either.right(List.of(mockTxLine)));

        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().organisationId("org").from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().organisationId("org").build());

        verify(applicationEventPublisher, times(3)).publishEvent(any(TransactionBatchChunkEvent.class));
        verify(ingestionRepository).findById("id");
        verify(transactionConverter).convert("orgId", "id", List.of(mockTxLine));
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository, netSuiteParser, transactionConverter);
    }

    @Test
    void testConintueERPExtraction_exceptionHandling() {
        when(ingestionRepository.findById("id")).thenThrow(new RuntimeException("TestException"));

        netSuiteExtractionService.continueERPExtraction("id", "orgId", UserExtractionParameters.builder().from(LocalDate.now()).to(LocalDate.now()).build(), SystemExtractionParameters.builder().build());

        verify(applicationEventPublisher).publishEvent(any(TransactionBatchFailedEvent.class));
        verify(ingestionRepository).findById("id");
        verifyNoMoreInteractions(applicationEventPublisher, ingestionRepository);
    }

}
