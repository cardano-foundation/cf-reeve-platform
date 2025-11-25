package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.bloxbean.cardano.client.api.exception.ApiException;
import io.vavr.control.Either;
import org.apache.commons.lang3.tuple.Pair;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.API3BlockchainTransaction;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.L1Submission;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
public class BlockchainReportsDispatchTest {

    @Mock
    private OrganisationPublicApi organisationPublicApi;
    @Mock
    private ReportEntityRepositoryGateway reportEntityRepositoryGateway;
    @Mock
    private DispatchingStrategy<ReportEntity> dispatchingStrategy;
    @Mock
    private API3L1TransactionCreator api3L1TransactionCreator;
    @Mock
    private TransactionSubmissionService transactionSubmissionService;
    @Mock
    private LedgerUpdatedEventPublisher ledgerUpdatedEventPublisher;

    @InjectMocks
    private BlockchainReportsDispatcher blockchainReportsDispatcher;

    @Test
    void dispatchReports_emptyOrgs() {
        when(organisationPublicApi.listAll()).thenReturn(List.of());

        blockchainReportsDispatcher.dispatchReports();

        verify(organisationPublicApi).listAll();
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoInteractions(dispatchingStrategy);
        verifyNoInteractions(reportEntityRepositoryGateway);
        verifyNoInteractions(ledgerUpdatedEventPublisher);
        verifyNoInteractions(reportEntityRepositoryGateway);
    }

    @Test
    void dispatchReports_emptyReportEntities() {
        Organisation organisation = mock(Organisation.class);
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsByStatus("org123", 50)).thenReturn(Set.of());

        blockchainReportsDispatcher.dispatchReports();

        verify(organisationPublicApi).listAll();
        verify(reportEntityRepositoryGateway).findReportsByStatus("org123", 50);
        verifyNoMoreInteractions(organisationPublicApi);
        verifyNoMoreInteractions(reportEntityRepositoryGateway);
        verifyNoInteractions(dispatchingStrategy);
        verifyNoInteractions(ledgerUpdatedEventPublisher);
    }

    @Test
    void dispatchReports_problemSerializing() {
        Organisation organisation = mock(Organisation.class);
        ReportEntity reportEntity = mock(ReportEntity.class);
        Problem problem = mock(Problem.class);
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(problem.getDetail()).thenReturn("problem");
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsByStatus("org123", 50)).thenReturn(Set.of(reportEntity));
        when(api3L1TransactionCreator.pullBlockchainTransaction(reportEntity)).thenReturn(Either.left(problem));
        when(reportEntity.getId()).thenReturn("report123");
        blockchainReportsDispatcher.dispatchReports();

        verify(organisationPublicApi).listAll();
        verify(reportEntityRepositoryGateway).findReportsByStatus("org123", 50);
        verify(api3L1TransactionCreator).pullBlockchainTransaction(reportEntity);
        verify(reportEntity).setL1SubmissionData(any());
        verify(reportEntityRepositoryGateway).storeReport(reportEntity);

        ArgumentCaptor<Set<Pair<String, L1SubmissionData>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(ledgerUpdatedEventPublisher).sendReportLedgerUpdatedEvents(eq("org123"), captor.capture());

        Set<Pair<String, L1SubmissionData>> capturedSet = captor.getValue();
        assertEquals(1, capturedSet.size());
        Pair<String, L1SubmissionData> pair = capturedSet.iterator().next();
        assertEquals("report123", pair.getLeft());
        assertEquals(1L, pair.getRight().getPublishRetry());
        assertEquals(Optional.of(BlockchainPublishStatus.STORED), pair.getRight().getPublishStatus());
        assertEquals(Optional.of("problem"), pair.getRight().getPublishStatusErrorReason());
    }

    @Test
    void dispatchReports_success() throws ApiException {
        Organisation organisation = mock(Organisation.class);
        org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation publisherOrg = mock(org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation.class);
        ReportEntity reportEntity = mock(ReportEntity.class);
        API3BlockchainTransaction transaction = mock(API3BlockchainTransaction.class);

        L1Submission l1Submission = mock(L1Submission.class);
        when(l1Submission.txHash()).thenReturn("txHash123");
        when(l1Submission.absoluteSlot()).thenReturn(Optional.of(1L));
        when(organisationPublicApi.listAll()).thenReturn(List.of(organisation));
        when(organisation.getId()).thenReturn("org123");
        when(reportEntityRepositoryGateway.findReportsByStatus("org123", 50)).thenReturn(Set.of(reportEntity));
        when(api3L1TransactionCreator.pullBlockchainTransaction(reportEntity)).thenReturn(Either.right(transaction));
        when(reportEntity.getOrganisation()).thenReturn(publisherOrg);
        when(publisherOrg.getId()).thenReturn("org123");
        when(transaction.serialisedTxData()).thenReturn(new byte[0]);
        when(transaction.receiverAddress()).thenReturn("receiver123");
        when(transactionSubmissionService.submitTransactionWithPossibleConfirmation(new byte[0], "receiver123"))
                .thenReturn(l1Submission);

        blockchainReportsDispatcher.dispatchReports();

        verify(organisationPublicApi).listAll();
        verify(reportEntityRepositoryGateway).findReportsByStatus("org123", 50);
        verify(api3L1TransactionCreator).pullBlockchainTransaction(reportEntity);
        verify(reportEntity).setL1SubmissionData(any());
        verify(reportEntityRepositoryGateway).storeReport(reportEntity);


        ArgumentCaptor<Set<Pair<String, L1SubmissionData>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(ledgerUpdatedEventPublisher).sendReportLedgerUpdatedEvents(eq("org123"), captor.capture());

        Set<Pair<String, L1SubmissionData>> capturedSet = captor.getValue();
        assertEquals(1, capturedSet.size());
        Pair<String, L1SubmissionData> pair = capturedSet.iterator().next();
        assertEquals(Optional.of("txHash123"), pair.getRight().getTransactionHash());
    }
}
