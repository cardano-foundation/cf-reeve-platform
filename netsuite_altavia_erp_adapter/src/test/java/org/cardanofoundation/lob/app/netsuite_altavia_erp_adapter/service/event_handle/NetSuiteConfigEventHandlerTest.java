package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.context.ApplicationEventPublisher;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteConfigService;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigStatus;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;
import org.cardanofoundation.lob.app.support.modulith.EventMetadata;

@ExtendWith(MockitoExtension.class)
class NetSuiteConfigEventHandlerTest {

    @Mock
    private NetSuiteConfigService netSuiteConfigService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private NetSuiteConfigEventHandler handler;

    @Test
    void publishesTheAcknowledgementReturnedByTheService() {
        NetSuiteConfigUpsertedEvent event = NetSuiteConfigUpsertedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigUpsertedEvent.VERSION, "admin"))
                .organisationId("org-1").revision(1L)
                .baseUrl("https://base").tokenUrl("https://token")
                .clientId("client").certificateId("cert")
                .privateKeyEncrypted("v1:ENVELOPE")
                .build();

        NetSuiteConfigAppliedEvent ack = NetSuiteConfigAppliedEvent.builder()
                .metadata(EventMetadata.create(NetSuiteConfigAppliedEvent.VERSION))
                .organisationId("org-1").revision(1L)
                .storeStatus(NetSuiteConfigStatus.SUCCESS)
                .validationStatus(NetSuiteConfigStatus.SUCCESS)
                .build();

        when(netSuiteConfigService.apply(any(NetSuiteConfigUpsertedEvent.class))).thenReturn(ack);

        handler.handleNetSuiteConfigUpserted(event);

        verify(applicationEventPublisher).publishEvent(ack);
    }

}
