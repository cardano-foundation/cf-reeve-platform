package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.csv_erp_adapter.config.Constants;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.TransactionLine;
import org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.SystemExtractionParametersFactory;

@ExtendWith(MockitoExtension.class)
class CsvExtractionServiceTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private CsvExtractionFileStore temporaryFileStore;
    @Mock
    private SystemExtractionParametersFactory systemExtractionParametersFactory;
    @Mock
    private TransactionConverter transactionConverter;
    @Mock
    private CsvParser<TransactionLine> parser;

    @InjectMocks
    private CsvExtractionService csvExtractionService;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        Field field = CsvExtractionService.class.getDeclaredField("sendBatchSize");
        field.setAccessible(true);
        field.set(csvExtractionService, 1);
    }

    @Test
    void startNewExtraction_noSystemParameters() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);

        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.startNewExtraction("orgId", "userId", userExtractionParameters, null);

        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.NO_SYSTEM_PARAMETERS, value.getError().getSubCode());

    }

    @Test
    void startNewExtraction_emptyFile() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);

        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.right(systemExtractionParameters));

        csvExtractionService.startNewExtraction("orgId", "userId", userExtractionParameters, null);

        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.EMPTY_FILE, value.getError().getSubCode());
    }

    @Test
    void startNewExtraction_success() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);

        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.right(systemExtractionParameters));

        csvExtractionService.startNewExtraction("orgId", "userId", userExtractionParameters, new byte[2]);

        verify(temporaryFileStore).put(anyString(), eq("orgId"), any());
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchStartedEvent.class));
    }

    @Test
    void continueERPExtraction_extractionDataNotFound() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.empty());

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.BATCH_NOT_FOUND, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_organisationMismatch() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("differentOrgId");

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.ORGANISATION_MISMATCH, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_errorCSVParse() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(null);
        when(parser.parseCsv((byte[]) null, TransactionLine.class)).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.CSV_PARSING_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_emptyTxLines() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(new byte[]{1,2,3});
        when(parser.parseCsv(new byte[]{1,2,3}, TransactionLine.class)).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.CSV_PARSING_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_errorConverting() throws IOException {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);
        TransactionLine transactionLine = mock(TransactionLine.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(transactionLine)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.TRANSACTION_CONVERSION_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_success() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);
        TransactionLine line = mock(TransactionLine.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = new byte[2];

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(line)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of(transaction)));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchChunkEvent> captor = ArgumentCaptor.forClass(TransactionBatchChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchChunkEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("batchId", value.getBatchId());
        assertEquals(1, value.getTransactions().size());
        assertEquals(transaction, value.getTransactions().stream().iterator().next());
        assertEquals(TransactionBatchChunkEvent.Status.FINISHED, value.getStatus());
    }

    @Test
    void continueERPExtraction_emptyList() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);
        TransactionLine line = mock(TransactionLine.class);

        when(temporaryFileStore.take("batchId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = new byte[2];

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(line)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of()));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileStore).take("batchId");
        ArgumentCaptor<TransactionBatchChunkEvent> captor = ArgumentCaptor.forClass(TransactionBatchChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchChunkEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("batchId", value.getBatchId());
        assertEquals(0, value.getTransactions().size());
        assertEquals(TransactionBatchChunkEvent.Status.FINISHED, value.getStatus());
    }


    @Test
    void startNewReconcilation_success() {

        csvExtractionService.startNewReconciliation("orgId", "userId", null, LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).put(anyString(), eq("orgId"), any());
        verify(applicationEventPublisher).publishEvent(any(ReconcilationStartedEvent.class));
    }

    @Test
    void continueERPReconcilation_ingestionNotFound() {
        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.empty());

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.RECONCILATION_NOT_FOUND, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_organisationMismatch() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("differentOrgId");

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.ORGANISATION_MISMATCH, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_csvParseError() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(null);
        when(parser.parseCsv((byte[]) null, TransactionLine.class)).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.CSV_PARSING_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_emptyTransactionLines() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(new byte[]{1,2,3});
        when(parser.parseCsv(new byte[]{1,2,3}, TransactionLine.class)).thenReturn(Either.right(List.of()));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.NO_TRANSACTION_LINES, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_transactionConversionError() throws IOException {
        ExtractionData extractionData = mock(ExtractionData.class);
        TransactionLine line = mock(TransactionLine.class);

        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(line)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.left(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.TRANSACTION_CONVERSION_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_success() throws IOException {
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);
        TransactionLine line = mock(TransactionLine.class);

        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(line)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of(transaction)));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationChunkEvent> captor = ArgumentCaptor.forClass(ReconcilationChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationChunkEvent value = captor.getValue();
        assertEquals(LocalDate.of(2025, 1, 1), value.getFrom());
        assertEquals(LocalDate.of(2025, 12, 31), value.getTo());
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("reconcilationId", value.getReconciliationId());
        assertEquals(1, value.getTransactions().size());
        assertEquals(transaction, value.getTransactions().stream().iterator().next());
    }

    @Test
    void continueERPReconcilation_emptyLines() throws Exception {
        ExtractionData extractionData = mock(ExtractionData.class);
        TransactionLine line = mock(TransactionLine.class);
        when(temporaryFileStore.take("reconcilationId")).thenReturn(Optional.of(extractionData));
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(parser.parseCsv(bytes, TransactionLine.class)).thenReturn(Either.right(List.of(line)));
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of()));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId", LocalDate.EPOCH, LocalDate.EPOCH);

        verify(temporaryFileStore).take("reconcilationId");
        ArgumentCaptor<ReconcilationChunkEvent> captor = ArgumentCaptor.forClass(ReconcilationChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationChunkEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("reconcilationId", value.getReconciliationId());
        assertEquals(0, value.getTransactions().size());
    }



}
