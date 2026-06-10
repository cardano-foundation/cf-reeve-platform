package org.cardanofoundation.lob.app.blockchain_publisher.service.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_publisher.domain.core.BlockchainPublishStatus;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApi;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class SpendingEventConverterTest {

    private final OrganisationPublicApi organisationPublicApi = mock(OrganisationPublicApi.class);
    private final SpendingEventConverter converter = new SpendingEventConverter(organisationPublicApi);

    private void stubOrganisation() {
        Organisation org = mock(Organisation.class);
        when(org.getId()).thenReturn("org1");
        when(org.getName()).thenReturn("Org One");
        when(org.getCountryCode()).thenReturn("CH");
        when(org.getTaxIdNumber()).thenReturn("TAX1");
        when(org.getCurrencyId()).thenReturn("ISO_4217:CHF");
        when(organisationPublicApi.findByOrganisationId("org1")).thenReturn(Optional.of(org));
    }

    private SpendingEventPublishView.Currency usd() {
        return SpendingEventPublishView.Currency.builder().id("ISO_4217:USD").custCode("USD").build();
    }

    @Test
    void convertsAllEventFields() {
        stubOrganisation();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1")
                .projectId("proj-1")
                .eventType(EventType.SPENDING)
                .date(LocalDate.of(2026, 6, 9))
                .fundingId("fund-1")
                .activityId("act-1")
                .activityTitle("Activity Title")
                .roundId("round-1")
                .fundingTx("ftx-1")
                .fundingDocHash("doc-hash-1")
                .milestoneId("ms-1")
                .amount(new BigDecimal("123.45"))
                .currency(usd())
                .items(List.of())
                .milestones(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertEquals("event-1", entity.getEventId());
        assertEquals("proj-1", entity.getProjectId());
        assertEquals(EventType.SPENDING, entity.getEventType());
        assertEquals(LocalDate.of(2026, 6, 9), entity.getEventDate());
        assertEquals("fund-1", entity.getFundingId());
        assertEquals("act-1", entity.getActivityId());
        assertEquals("Activity Title", entity.getActivityTitle());
        assertEquals("round-1", entity.getRoundId());
        assertEquals("ftx-1", entity.getFundingTx());
        assertEquals("doc-hash-1", entity.getFundingDocHash());
        assertEquals("ms-1", entity.getMilestoneId());
        assertEquals(new BigDecimal("123.45"), entity.getTotalAmount());
        assertEquals("USD", entity.getCurrency());
        assertEquals("ISO_4217:USD", entity.getCurrencyId());
        assertEquals("org1", entity.getOrganisationId());
        assertEquals(Optional.of(BlockchainPublishStatus.STORED), entity.getL1SubmissionData().flatMap(d -> d.getPublishStatus()));
    }

    @Test
    void convertsItemsAndMilestonesWithBacklink() {
        stubOrganisation();

        SpendingEventPublishView.SpendItem item = SpendingEventPublishView.SpendItem.builder()
                .itemId("item-1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .currency(usd())
                .fxRate(new BigDecimal("0.85"))
                .amountRcy(new BigDecimal("85.00"))
                .spendDate(LocalDate.of(2025, 4, 3))
                .documentHash("hash-1")
                .notes("note")
                .build();

        SpendingEventPublishView.Milestone milestone = SpendingEventPublishView.Milestone.builder()
                .milestoneId("ms-1")
                .label("Milestone AB")
                .amount(new BigDecimal("50.00"))
                .currency(usd())
                .date(LocalDate.of(2025, 6, 30))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1")
                .projectId("proj-1")
                .eventType(EventType.SPENDING)
                .date(LocalDate.of(2026, 6, 9))
                .fundingId("fund-1")
                .activityId("act-1")
                .amount(new BigDecimal("100.00"))
                .currency(usd())
                .items(List.of(item))
                .milestones(List.of(milestone))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertEquals(1, entity.getSpendingItems().size());
        var itemEntity = entity.getSpendingItems().get(0);
        assertEquals("item-1", itemEntity.getItemId());
        assertEquals("USD", itemEntity.getCurrency());
        assertEquals("ISO_4217:USD", itemEntity.getCurrencyId());
        assertEquals(new BigDecimal("0.85"), itemEntity.getFxRate());
        assertEquals("hash-1", itemEntity.getDocumentHash());
        assertEquals(entity, itemEntity.getEvent());

        assertEquals(1, entity.getMilestoneAllocations().size());
        var allocationEntity = entity.getMilestoneAllocations().get(0);
        assertEquals("ms-1", allocationEntity.getMilestoneId());
        assertEquals("Milestone AB", allocationEntity.getMilestoneLabel());
        assertEquals(new BigDecimal("50.00"), allocationEntity.getAllocatedAmount());
        assertEquals("ISO_4217:USD", allocationEntity.getCurrencyId());
        assertEquals(entity, allocationEntity.getEvent());
    }

}
