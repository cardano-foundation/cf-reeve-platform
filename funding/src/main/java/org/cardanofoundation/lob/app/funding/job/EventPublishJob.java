package org.cardanofoundation.lob.app.funding.job;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.funding.domain.entity.SpendingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.events.SpendingEventsPublishCommand;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.funding.repository.SpendingEventRepository;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.collections.Partitions;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventPublishJob {

    private final SpendingEventRepository  spendingEventRepository;
    private final SpendingEventService spendingEventService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OrganisationPublicApi  organisationPublicApi;
    @Value("${lob.funding.publish.batch_size:100}")
    private int dispatchBatchSize;

    @Scheduled(
            fixedDelayString = "${lob.funding.publish.fixed_delay:PT1M}",
            initialDelayString = "${lob.funding.publish.initial_delay:PT10S}"
    )
    public void publishEvents() {
        log.debug("Publishing Events");
        for (Organisation organisation : organisationPublicApi.listAll()) {
            Set<SpendingEventEntity> allPublishableEvents = spendingEventRepository.findAllToBePublished(organisation.getId());
            Set<SpendingEventPublishView> spendingEventViews = allPublishableEvents.stream().map(spendingEventService::toPublishView).collect(Collectors.toSet());

            for (Partitions.Partition<SpendingEventPublishView> partition : Partitions.partition(spendingEventViews, dispatchBatchSize)) {
                Set<SpendingEventPublishView> eventViewSet = partition.asSet();
                log.info("Publishing {} events for organisationId: {}", eventViewSet.size(), organisation.getId());
                applicationEventPublisher.publishEvent(new SpendingEventsPublishCommand(organisation.getId(), eventViewSet));
            }
            allPublishableEvents.forEach(spendingEventEntity -> {
                spendingEventEntity.setPublishedAt(LocalDateTime.now());
                spendingEventEntity.setLedgerDispatchStatus(LedgerDispatchStatus.DISPATCHED);
            });
            spendingEventRepository.saveAll(allPublishableEvents);
        }

    }

}
