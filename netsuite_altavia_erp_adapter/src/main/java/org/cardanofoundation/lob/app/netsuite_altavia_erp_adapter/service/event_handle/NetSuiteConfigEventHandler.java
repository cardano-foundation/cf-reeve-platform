package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.event_handle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.internal.NetSuiteConfigService;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigAppliedEvent;
import org.cardanofoundation.lob.app.organisation.domain.event.netsuite.NetSuiteConfigUpsertedEvent;

/**
 * Bridges the inbound configuration event to the config service and publishes the resulting
 * acknowledgement.
 * <p>
 * Deliberately not {@code @Async}: the acknowledgement is published on the consumer thread so
 * store-then-ack ordering stays predictable. Not a {@code @Service} either — this module's beans
 * are declared explicitly in the implementing application's config.
 */
@Slf4j
@RequiredArgsConstructor
public class NetSuiteConfigEventHandler {

    private final NetSuiteConfigService netSuiteConfigService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @EventListener
    public void handleNetSuiteConfigUpserted(NetSuiteConfigUpsertedEvent event) {
        log.info("Handling NetSuiteConfigUpsertedEvent for organisation {} revision {}",
                event.getOrganisationId(), event.getRevision());

        NetSuiteConfigAppliedEvent ack = netSuiteConfigService.apply(event);
        applicationEventPublisher.publishEvent(ack);

        log.info("Published NetSuiteConfigAppliedEvent for organisation {}: store={}, validation={}",
                ack.getOrganisationId(), ack.getStoreStatus(), ack.getValidationStatus());
    }

}
