package org.cardanofoundation.lob.app.blockchain_publisher.service.converter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingItemEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.L1SubmissionData;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs.Organisation;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;

/**
 * Converts a funding-module {@link SpendingEventPublishView} into a blockchain-publisher
 * {@link SpendingEventEntity} (with its spending items / milestone allocations), ready to be stored in
 * {@code STORED} state for later dispatch and metadata serialisation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SpendingEventConverter {

    private final OrganisationPublicApi organisationPublicApi;

    public SpendingEventEntity convertToDbDetached(String organisationId, SpendingEventPublishView view) {
        SpendingEventEntity entity = new SpendingEventEntity();
        entity.setEventId(view.getEventId());
        entity.setProjectId(view.getProjectId());
        entity.setEventType(view.getEventType());
        entity.setEventDate(view.getDate());

        entity.setFundingId(view.getFundingId());
        entity.setActivityId(view.getActivityId());
        entity.setActivityTitle(view.getActivityTitle());
        entity.setFundingTx(view.getFundingTx());
        entity.setFundingDocHash(view.getFundingDocHash());

        entity.setTotalAmount(orZero(view.getAmount()));
        entity.setCurrency(custCode(view.getCurrency()));
        entity.setCurrencyId(currencyId(view.getCurrency()));

        entity.setOrganisation(resolveOrganisation(organisationId));
        entity.setL1SubmissionData(Optional.of(L1SubmissionData.builder()
                .publishStatus(BlockchainPublishStatus.STORED)
                .build()));

        entity.setSpendingItems(convertItems(entity, view.getItems()));
        entity.setMilestoneAllocations(convertMilestones(entity, view.getMilestones()));

        return entity;
    }

    private List<SpendingItemEntity> convertItems(SpendingEventEntity event, List<SpendingEventPublishView.SpendItem> items) {
        if (items == null) {
            return List.of();
        }

        return items.stream()
                .map(item -> SpendingItemEntity.builder()
                        .itemId(item.getItemId())
                        .event(event)
                        .category(item.getCategory())
                        .vendor(item.getVendor())
                        .amountFcy(orZero(item.getAmountFcy()))
                        .amountRcy(orZero(item.getAmountRcy()))
                        .currency(custCode(item.getCurrency()))
                        .currencyId(currencyId(item.getCurrency()))
                        .fxRate(orZero(item.getFxRate()))
                        .spendDate(item.getSpendDate())
                        .documentHash(item.getDocumentHash())
                        .notes(item.getNotes())
                        .build())
                .toList();
    }

    private List<EventMilestoneAllocationEntity> convertMilestones(SpendingEventEntity event, List<SpendingEventPublishView.Milestone> milestones) {
        if (milestones == null) {
            return List.of();
        }

        return milestones.stream()
                .map(milestone -> EventMilestoneAllocationEntity.builder()
                        .event(event)
                        .milestoneId(milestone.getMilestoneId())
                        .milestoneLabel(milestone.getMilestoneLabel())
                        .expectedCost(milestone.getExpectedCost())
                        .allocatedAmount(milestone.getAllocatedAmount())
                        .currency(custCode(milestone.getCurrency()))
                        .currencyId(currencyId(milestone.getCurrency()))
                        .dueDate(milestone.getDueDate())
                        .build())
                .toList();
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
                .orElseThrow(() -> new IllegalStateException("Organisation not found for id: " + organisationId));
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
