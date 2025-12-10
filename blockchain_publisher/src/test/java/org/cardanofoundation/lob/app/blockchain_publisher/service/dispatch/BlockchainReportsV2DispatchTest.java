package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reportsV2.ReportV2Entity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class BlockchainReportsV2DispatchTest {

    @Mock
    private OrganisationPublicApi organisationPublicApi;
    @Mock
    private ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    @Mock
    private API3L1TransactionCreator api3L1TransactionCreator;
    @Mock
    private TransactionSubmissionService transactionSubmissionService;
    @Mock
    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @InjectMocks
    private BlockchainReportsV2Dispatcher dispatcher;

    @Test
    void dispatchReports_noReportsToDispatch() {
        Organisation organisation = mock(Organisation.class);
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsV2ByStatus("org123", 50)).thenReturn(Set.of());

        dispatcher.dispatchReports();

        verifyNoInteractions(api3L1TransactionCreator);
        verifyNoInteractions(transactionSubmissionService);
        verifyNoInteractions(ledgerUpdatedEventPublisher);
    }

    @Test
    void dispatchReports_errorPullBlockchainTransaction() {
        Organisation organisation = mock(Organisation.class);
        ReportV2Entity reportEntity = mock(ReportV2Entity.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsV2ByStatus("org123", 50)).thenReturn(Set.of(reportEntity));
        when(api3L1TransactionCreator.pullBlockchainTransaction(reportEntity)).thenReturn(Either.left(Problem.builder().withDetail("Detail").build()));
        when(reportEntity.getL1SubmissionData()).thenReturn(Optional.empty());
        dispatcher.dispatchReports();

        verifyNoInteractions(transactionSubmissionService);
        verify(reportEntity).setL1SubmissionData(argThat(arg ->
                arg.isPresent()
                && arg.get().getPublishStatusErrorReason().isPresent()
                && arg.get().getPublishStatusErrorReason().get().equals("Detail")
                && arg.get().getPublishRetry().equals(1L)
        ));
    }

    @Test
    void dispatchReports_success() throws ApiException {
        Organisation organisation = mock(Organisation.class);
        ReportV2Entity reportEntity = mock(ReportV2Entity.class);
        API3BlockchainTransaction api3BlockchainTransaction = mock(API3BlockchainTransaction.class);
        L1Submission l1Submission = new L1Submission("txHash123", Optional.of(1L), true);
        L1SubmissionData l1SubmissionData = mock(L1SubmissionData.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsV2ByStatus("org123", 50)).thenReturn(Set.of(reportEntity));
        when(api3L1TransactionCreator.pullBlockchainTransaction(reportEntity)).thenReturn(Either.right(api3BlockchainTransaction));
        when(reportEntity.getL1SubmissionData()).thenReturn(Optional.empty());
        when(api3BlockchainTransaction.serialisedTxData()).thenReturn(new byte[0]);
        when(api3BlockchainTransaction.receiverAddress()).thenReturn("receiver123");
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(new byte[0], "receiver123")).thenReturn(l1Submission);
        when(reportEntity.getL1SubmissionData()).thenReturn(Optional.of(l1SubmissionData));

        dispatcher.dispatchReports();

        verify(reportEntity).setL1SubmissionData(argThat(arg ->
                arg.isPresent()
                        && arg.get().getPublishStatusErrorReason().isEmpty()
                        && arg.get().getPublishStatus().equals(Optional.of(BlockchainPublishStatus.SUBMITTED))
        ));
        verify(reportEntityRepositoryGateway).storeReport(reportEntity);
    }

    @Test
    void dispatchReports_submissionError() throws ApiException {
        Organisation organisation = mock(Organisation.class);
        ReportV2Entity reportEntity = mock(ReportV2Entity.class);
        API3BlockchainTransaction api3BlockchainTransaction = mock(API3BlockchainTransaction.class);
        L1SubmissionData l1SubmissionData = mock(L1SubmissionData.class);

        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsV2ByStatus("org123", 50)).thenReturn(Set.of(reportEntity));
        when(api3L1TransactionCreator.pullBlockchainTransaction(reportEntity)).thenReturn(Either.right(api3BlockchainTransaction));
        when(reportEntity.getL1SubmissionData()).thenReturn(Optional.empty());
        when(api3BlockchainTransaction.serialisedTxData()).thenReturn(new byte[0]);
        when(api3BlockchainTransaction.receiverAddress()).thenReturn("receiver123");
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(new byte[0], "receiver123")).thenThrow(ApiException.class);
        when(reportEntity.getL1SubmissionData()).thenReturn(Optional.of(l1SubmissionData));

        dispatcher.dispatchReports();

        verify(reportEntity).setL1SubmissionData(argThat(arg ->
                arg.isPresent()
                        && arg.get().getPublishStatusErrorReason().isPresent()
                        && arg.get().getPublishRetry().equals(1L)
        ));
    }
}
