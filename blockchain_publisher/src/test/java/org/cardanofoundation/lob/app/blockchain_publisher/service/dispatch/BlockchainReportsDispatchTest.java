package org.cardanofoundation.lob.app.blockchain_publisher.service.dispatch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.reports.ReportEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.repository.ReportEntityRepositoryGateway;
import org.cardanofoundation.lob.app.blockchain_publisher.service.API3L1TransactionCreator;
import org.cardanofoundation.lob.app.blockchain_publisher.service.event_publish.LedgerUpdatedEventPublisher;
import org.cardanofoundation.lob.app.blockchain_publisher.service.transation_submit.TransactionSubmissionService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

@ExtendWith(MockitoExtension.class)
class BlockchainReportsDispatchTest {

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
}
