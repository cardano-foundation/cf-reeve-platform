package org.cardanofoundation.lob.app.blockchain_publisher.service.converter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventProjectAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * Converts a funding-module {@link SpendingEventPublishView} into a blockchain-publisher
 * {@link SpendingEventEntity} (with its spending items and per-project allocations), ready to be
 * stored in {@code STORED} state for later dispatch and metadata serialisation. The view's nested
 * project → milestone structure is preserved (not flattened).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpendingEventConverter {

    private final OrganisationPublicApi organisationPublicApi;

    public SpendingEventEntity convertToDbDetached(String organisationId, SpendingEventPublishView view) {
        SpendingEventEntity entity = new SpendingEventEntity();
        entity.setEventId(view.getEventId());
        entity.setEventType(view.getEventType());
        entity.setEventDate(view.getDate());

        entity.setFundingId(view.getFundingId());
        entity.setFundingTx(view.getFundingHash());
        entity.setFundingEntity(view.getFundingEntity());

        entity.setTotalAmount(orZero(view.getAmount()));
        entity.setCurrency(custCode(view.getCurrency()));
        entity.setCurrencyId(currencyId(view.getCurrency()));

        entity.setOrganisation(resolveOrganisation(organisationId));
        entity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .publishStatus(BlockchainPublishStatus.STORED)
                .build()));

        entity.setProjectAllocations(convertAllocations(entity, view.getProjectAllocations()));

        return entity;
    }

    private List<EventProjectAllocationEntity> convertAllocations(SpendingEventEntity event, List<SpendingEventPublishView.ProjectAllocation> allocations) {
        if (allocations == null) return new ArrayList<>();
        return allocations.stream()
                .map(allocation -> {
                    EventProjectAllocationEntity entity = EventProjectAllocationEntity.builder()
                            .event(event)
                            .projectId(allocation.getExternalProjectId())
                            .projectTitle(allocation.getProjectTitle())
                            .subProjectTitle(allocation.getSubProjectTitle())
                            .build();
                    entity.setMilestones(convertMilestones(entity, allocation.getMilestones()));
                    return entity;
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<EventMilestoneAllocationEntity> convertMilestones(EventProjectAllocationEntity allocation, List<SpendingEventPublishView.Milestone> milestones) {
        if (milestones == null) return new ArrayList<>();
        return milestones.stream()
                .map(milestone -> EventMilestoneAllocationEntity.builder()
                        .allocation(allocation)
                        .milestoneId(milestone.getMilestoneId())
                        .milestoneTitle(milestone.getMilestoneTitle())
                        .allocatedAmount(milestone.getAllocatedAmount())
                        .category(milestone.getCategory())
                        .vendor(milestone.getVendor())
                        .amountFcy(milestone.getAmountFcy())
                        .amountRcy(milestone.getAmountRcy())
                        .currency(custCode(milestone.getSpendCurrency()))
                        .currencyId(currencyId(milestone.getSpendCurrency()))
                        .fxRate(milestone.getFxRate())
                        .spendDate(milestone.getSpendDate())
                        .documentHash(milestone.getDocumentHash())
                        .notes(milestone.getNotes())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Organisation resolveOrganisation(String organisationId) {
        return organisationPublicApi.findByOrganisationId(organisationId)
                .map(org -> Organisation.builder()
                        .id(org.getId())
                        .name(org.getName())
                        .countryCode(org.getCountryCode())
                        .taxIdNumber(org.getTaxIdNumber())
                        .currencyId(org.getCurrencyId())
                        .build())
                .orElseThrow(() -> new IllegalStateException("Organisation not found: " + organisationId));
    }

    private static String custCode(SpendingEventPublishView.Currency currency) {
        return currency != null ? currency.getCustCode() : null;
    }

    private static String currencyId(SpendingEventPublishView.Currency currency) {
        return currency != null ? currency.getId() : null;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

}
