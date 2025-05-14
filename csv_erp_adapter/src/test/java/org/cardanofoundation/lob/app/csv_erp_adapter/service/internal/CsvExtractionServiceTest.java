package org.cardanofoundation.lob.app.csv_erp_adapter.service.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;

import com.google.common.cache.Cache;
import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Transaction;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationChunkEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationFailedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.reconcilation.ReconcilationStartedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.SystemExtractionParametersFactory;
import org.cardanofoundation.lob.app.csv_erp_adapter.config.Constants;
import org.cardanofoundation.lob.app.csv_erp_adapter.domain.ExtractionData;

@ExtendWith(MockitoExtension.class)
class CsvExtractionServiceTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private Cache<String, ExtractionData> temporaryFileCache;
    @Mock
    private SystemExtractionParametersFactory systemExtractionParametersFactory;
    @Mock
    private TransactionConverter transactionConverter;

    @InjectMocks
    private CsvExtractionService csvExtractionService;

    @Test
    void startNewExtraction_noSystemParameters() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);

        when(systemExtractionParametersFactory.createSystemExtractionParameters("orgId")).thenReturn(Either.left(Problem.builder().build()));

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

        verify(temporaryFileCache).put(anyString(), any(ExtractionData.class));
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchStartedEvent.class));
    }

    @Test
    void continueERPExtraction_extractionDataNotFound() {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(null);

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

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("differentOrgId");

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
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

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(null);

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
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

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(new byte[]{1,2,3});

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.NO_TRANSACTION_LINES, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_errorConverting() throws IOException {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.left(Problem.builder().build()));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
        ArgumentCaptor<TransactionBatchFailedEvent> captor = ArgumentCaptor.forClass(TransactionBatchFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        TransactionBatchFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.TRANSACTION_CONVERSION_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPExtraction_success() throws IOException {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of(transaction)));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
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
    void continueERPExtraction_emptyList() throws IOException {
        UserExtractionParameters userExtractionParameters = mock(UserExtractionParameters.class);
        SystemExtractionParameters systemExtractionParameters = mock(SystemExtractionParameters.class);
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);

        when(temporaryFileCache.getIfPresent("batchId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of()));

        csvExtractionService.continueERPExtraction("batchId", "orgId", userExtractionParameters, systemExtractionParameters);

        verify(temporaryFileCache).getIfPresent("batchId");
        verify(temporaryFileCache).invalidate("batchId");
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

        verify(temporaryFileCache).put(anyString(), any(ExtractionData.class));
        verify(applicationEventPublisher).publishEvent(any(ReconcilationStartedEvent.class));
    }

    @Test
    void continueERPReconcilation_ingestionNotFound() {
        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(null);

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.RECONCILATION_NOT_FOUND, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_organisationMismatch() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("differentOrgId");

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.ORGANISATION_MISMATCH, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_csvParseError() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(null);

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.CSV_PARSING_ERROR, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_emptyTransactionLines() {
        ExtractionData extractionData = mock(ExtractionData.class);

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        when(extractionData.file()).thenReturn(new byte[]{1,2,3});

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
        ArgumentCaptor<ReconcilationFailedEvent> captor = ArgumentCaptor.forClass(ReconcilationFailedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationFailedEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals(Constants.NO_TRANSACTION_LINES, value.getError().getSubCode());
    }

    @Test
    void continueERPReconcilation_transactionConversionError() throws IOException {
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.left(Problem.builder().build()));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
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

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of(transaction)));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
        ArgumentCaptor<ReconcilationChunkEvent> captor = ArgumentCaptor.forClass(ReconcilationChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationChunkEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("reconcilationId", value.getReconciliationId());
        assertEquals(1, value.getTransactions().size());
        assertEquals(transaction, value.getTransactions().stream().iterator().next());
    }

    @Test
    void continueERPReconcilation_emptyLines() throws Exception {
        ExtractionData extractionData = mock(ExtractionData.class);
        Transaction transaction = mock(Transaction.class);

        when(temporaryFileCache.getIfPresent("reconcilationId")).thenReturn(extractionData);
        when(extractionData.organisationId()).thenReturn("orgId");
        byte[] bytes = getClass()
                .getClassLoader()
                .getResourceAsStream("testData.csv") // adjust the path
                .readAllBytes();

        when(extractionData.file()).thenReturn(bytes);
        when(transactionConverter.convertToTransaction(any(), any(), any())).thenReturn(Either.right(List.of()));

        csvExtractionService.continueERPReconciliation("reconcilationId", "orgId");

        verify(temporaryFileCache).getIfPresent("reconcilationId");
        verify(temporaryFileCache).invalidate("reconcilationId");
        ArgumentCaptor<ReconcilationChunkEvent> captor = ArgumentCaptor.forClass(ReconcilationChunkEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        ReconcilationChunkEvent value = captor.getValue();
        assertEquals("orgId", value.getOrganisationId());
        assertEquals("reconcilationId", value.getReconciliationId());
        assertEquals(0, value.getTransactions().size());
    }



}
