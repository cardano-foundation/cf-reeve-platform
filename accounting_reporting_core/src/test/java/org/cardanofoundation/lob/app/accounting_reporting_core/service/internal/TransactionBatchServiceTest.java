package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionBatchStatus;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.UserExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.FilteringParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionBatchEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.event.extraction.TransactionBatchCreatedEvent;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchAssocRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionBatchRepositoryGateway;

@ExtendWith(MockitoExtension.class)
class TransactionBatchServiceTest {

    @Mock
    private TransactionBatchRepositoryGateway transactionBatchRepositoryGateway;
    @Mock
    private TransactionBatchRepository transactionBatchRepository;
    @Mock
    private TransactionConverter transactionConverter;
    @Mock
    private TransactionBatchAssocRepository transactionBatchAssocRepository;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private TransactionBatchService transactionBatchService;

    @Test
    void findByIdTest() {
        transactionBatchService.findById("batchId");

        verify(transactionBatchRepository).findById("batchId");
        verifyNoMoreInteractions(transactionBatchRepository);
    }

    @Test
    void createTransactionBatchDuplicateBatchId() {
        when(transactionBatchRepository.findById("batchId")).thenReturn(Optional.of(new TransactionBatchEntity()));

        transactionBatchService.createTransactionBatch("batchId", "organisationId", null, null, "system");

        verify(transactionBatchRepository).findById("batchId");
        verifyNoMoreInteractions(transactionBatchRepository);
        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(transactionConverter);
    }

    @Test
    void createTransactionBatchSuccessfully() {
        when(transactionBatchRepository.findById("batchId")).thenReturn(Optional.empty());
        when(transactionConverter.convertToDbDetached(any(SystemExtractionParameters.class), any(UserExtractionParameters.class))).thenReturn(FilteringParameters.builder().build());

        transactionBatchService.createTransactionBatch("batchId", "organisationId", UserExtractionParameters.builder().build(), SystemExtractionParameters.builder().build(), "system123");

        TransactionBatchEntity transactionBatchEntity = new TransactionBatchEntity();
        transactionBatchEntity.setId("batchId");
        transactionBatchEntity.setTransactions(Set.of());
        transactionBatchEntity.setFilteringParameters(FilteringParameters.builder().build());
        transactionBatchEntity.setStatus(TransactionBatchStatus.CREATED);
        transactionBatchEntity.setCreatedBy("system123");

        verify(transactionBatchRepository).findById("batchId");
        verify(transactionBatchRepository).save(transactionBatchEntity);
        verify(transactionConverter).convertToDbDetached(any(SystemExtractionParameters.class), any(UserExtractionParameters.class));
        verify(applicationEventPublisher).publishEvent(any(TransactionBatchCreatedEvent.class));
        verifyNoMoreInteractions(transactionBatchRepository);
        verifyNoMoreInteractions(applicationEventPublisher);
        verifyNoMoreInteractions(transactionConverter);
    }

}
