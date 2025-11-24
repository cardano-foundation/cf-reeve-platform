package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.zalando.problem.Status.INTERNAL_SERVER_ERROR;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API1BlockchainTransactions;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.TransactionEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.TransactionEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API1L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class BlockchainTransactionsDispatcherTest {

    @Mock
    private TransactionEntityRepositoryGateway transactionEntityRepositoryGateway;
    @Mock
    private OrganisationPublicApi organisationPublicApi;
    @Mock
    private API1L1TransactionCreator l1TransactionCreator;
    @Mock
    private TransactionSubmissionService transactionSubmissionService;
    @Mock
    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;
    @Mock
    private DispatchingStrategy<TransactionEntity> dispatchingStrategy;
    @InjectMocks
    private BlockchainTransactionsDispatcher dispatcher;


    @Test
    void dispatchTransactionsNoOrganisations() {
        when(organisationPublicApi.listAll()).thenReturn(List.of());

        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();

        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoInteractions(transactionEntityRepositoryGateway);
        verifyNoInteractions(l1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
        verifyNoInteractions(ledgerUpdatedEventPublisher);
        verifyNoInteractions(dispatchingStrategy);
    }

    @Test
    void dispatchTransactionsNoTransactionToDispatch() {
        Organisation organisation = new Organisation();
        organisation.setId("organisationId");
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched("organisationId", 50)).thenReturn(Set.of());
        when(dispatchingStrategy.apply("organisationId", Set.of())).thenReturn(Set.of());

        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();
        verify(transactionEntityRepositoryGateway).findTransactionsReadyToBeDispatched("organisationId", 50);
        verify(dispatchingStrategy).apply("organisationId", Set.of());
        verify(transactionEntityRepositoryGateway).unlockTransactions(anySet());
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
        verifyNoMoreInteractions(dispatchingStrategy);
        verifyNoInteractions(l1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
        verifyNoInteractions(ledgerUpdatedEventPublisher);
    }

    @Test
    void dispatchTransactionsL1TransactionsProblem() {
        Organisation organisation = new Organisation();
        organisation.setId("organisationId");
        TransactionEntity transactionEntity = mock(TransactionEntity.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched("organisationId", 50)).thenReturn(Set.of());
        when(dispatchingStrategy.apply("organisationId", Set.of())).thenReturn(Set.of(transactionEntity));
        when(l1TransactionCreator.pullBlockchainTransaction(anyString(), anySet())).thenReturn(Either.left(Problem.builder()
                .withTitle("ERROR_CREATING_TRANSACTION")
                .withDetail("MESSAGE ERROR")
                .withStatus(INTERNAL_SERVER_ERROR)
                .build()));

        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();
        verify(transactionEntityRepositoryGateway).findTransactionsReadyToBeDispatched("organisationId", 50);
        verify(dispatchingStrategy).apply("organisationId", Set.of());
        verify(l1TransactionCreator).pullBlockchainTransaction("organisationId", Set.of(transactionEntity));
        verify(transactionEntityRepositoryGateway).lockTransactions(anySet());
        verify(transactionEntityRepositoryGateway).unlockTransactions(anySet());
        verify(ledgerUpdatedEventPublisher).sendTxLedgerUpdatedEvents(eq("organisationId"),anySet());
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
        verifyNoMoreInteractions(dispatchingStrategy);
        verifyNoMoreInteractions(l1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
    }

    @Test
    void dispatchTransactionsL1TransactionsEmpty() {
        Organisation organisation = new Organisation();
        organisation.setId("organisationId");
        TransactionEntity transactionEntity = mock(TransactionEntity.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched("organisationId", 50)).thenReturn(Set.of());
        when(dispatchingStrategy.apply("organisationId", Set.of())).thenReturn(Set.of(transactionEntity));
        when(l1TransactionCreator.pullBlockchainTransaction(anyString(), anySet())).thenReturn(Either.right(Optional.empty()));

        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();
        verify(transactionEntityRepositoryGateway).findTransactionsReadyToBeDispatched("organisationId", 50);
        verify(dispatchingStrategy).apply("organisationId", Set.of());
        verify(transactionEntityRepositoryGateway).lockTransactions(anySet());
        verify(l1TransactionCreator).pullBlockchainTransaction("organisationId", Set.of(transactionEntity));
        verify(transactionEntityRepositoryGateway).unlockTransactions(anySet());
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
        verifyNoMoreInteractions(dispatchingStrategy);
        verifyNoMoreInteractions(l1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
    }


    @Test
    void dispatchTransactionsL1TransactionsNull() {
        Organisation organisation = new Organisation();
        organisation.setId("organisationId");
        TransactionEntity transactionEntity = mock(TransactionEntity.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched("organisationId", 50)).thenReturn(Set.of());
        when(dispatchingStrategy.apply("organisationId", Set.of())).thenReturn(Set.of(transactionEntity));
        when(l1TransactionCreator.pullBlockchainTransaction(anyString(), anySet())).thenReturn(Either.right(Optional.ofNullable(null)));

        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();
        verify(transactionEntityRepositoryGateway).findTransactionsReadyToBeDispatched("organisationId", 50);
        verify(dispatchingStrategy).apply("organisationId", Set.of());
        verify(l1TransactionCreator).pullBlockchainTransaction("organisationId", Set.of(transactionEntity));
        verify(transactionEntityRepositoryGateway).lockTransactions(anySet());
        verify(transactionEntityRepositoryGateway).unlockTransactions(anySet());
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(transactionEntityRepositoryGateway);
        verifyNoMoreInteractions(dispatchingStrategy);
        verifyNoMoreInteractions(l1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
    }



    @Test
    void dispatchTransactionsSuccess() throws ApiException, InterruptedException {
        Organisation organisation = new Organisation();
        organisation.setId("organisationId");
        TransactionEntity transactionEntity = mock(TransactionEntity.class);
        API1BlockchainTransactions blockchainTransactions = mock(API1BlockchainTransactions.class);
        L1Submission l1Submission = mock(L1Submission.class);

        when(l1Submission.txHash()).thenReturn("txHash");
        when(blockchainTransactions.remainingTransactions()).thenReturn(new HashSet<>());
        when(blockchainTransactions.submittedTransactions()).thenReturn(new HashSet<>());
        when(blockchainTransactions.serialisedTxData()).thenReturn(new byte[0]);
        when(blockchainTransactions.receiverAddress()).thenReturn("receiverAddress");
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(transactionEntityRepositoryGateway.findTransactionsReadyToBeDispatched("organisationId", 50)).thenReturn(Set.of());
        when(dispatchingStrategy.apply("organisationId", Set.of())).thenReturn(Set.of(transactionEntity));
        when(l1TransactionCreator.pullBlockchainTransaction(anyString(), anySet())).thenReturn(Either.right(Optional.ofNullable(blockchainTransactions)));
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(eq(new byte[0]), anyString())).thenReturn(l1Submission);
        dispatcher.dispatchTransactions();

        verify(organisationPublicApi).listAll();
        verify(transactionEntityRepositoryGateway).findTransactionsReadyToBeDispatched("organisationId", 50);
        verify(dispatchingStrategy).apply("organisationId", Set.of());
        verify(l1TransactionCreator).pullBlockchainTransaction("organisationId", Set.of(transactionEntity));
        verify(transactionSubmissionService).submitTransactionWithPossibleConfirmation(eq(new byte[0]), anyString());
        verify(ledgerUpdatedEventPublisher).sendTxLedgerUpdatedEvents(null, new HashSet<>());
        verify(transactionEntityRepositoryGateway, times(3)).unlockTransactions(anySet());
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(dispatchingStrategy);
        verifyNoMoreInteractions(l1TransactionCreator);
        verifyNoMoreInteractions(transactionSubmissionService);
        verifyNoMoreInteractions(ledgerUpdatedEventPublisher);
    }


}
