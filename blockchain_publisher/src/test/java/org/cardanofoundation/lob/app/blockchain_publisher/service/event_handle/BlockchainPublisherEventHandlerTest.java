package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.service.BlockchainPublisherService;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;

@ExtendWith(MockitoExtension.class)
class BlockchainPublisherEventHandlerTest {

    @Mock
    private BlockchainPublisherService blockchainPublisherService;

    @InjectMocks
    private BlockchainPublisherEventHandler blockchainPublisherEventHandler;

    @Test
    void handleReportPublishingEvent() {
        PublishReportEvent event = mock(PublishReportEvent.class);

        blockchainPublisherEventHandler.handleReportPublishingEvent(event);

        verify(blockchainPublisherService, times(1)).storeReportsForDispatchLater(event);
        verifyNoMoreInteractions(blockchainPublisherService);
    }
}
