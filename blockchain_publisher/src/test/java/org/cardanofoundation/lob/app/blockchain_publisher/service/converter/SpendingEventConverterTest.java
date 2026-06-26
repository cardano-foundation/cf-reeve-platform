package org.cardanofoundation.lob.app.blockchain_publisher.service.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.EventProjectAllocationEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingEventEntity;
import org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.spending.SpendingItemEntity;
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

    private SpendingEventPublishView.ProjectAllocation allocation(String projectId, String projectTitle,
                                                                  String subProjectTitle,
                                                                  List<SpendingEventPublishView.Milestone> milestones) {
        return SpendingEventPublishView.ProjectAllocation.builder()
                .projectId(projectId).projectTitle(projectTitle).subProjectTitle(subProjectTitle)
                .milestones(milestones).build();
    }

    @Test
    void convertsAllEventFields() {
        stubOrganisation();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1")
                .organisationId("org1")
                .eventType(EventType.FUNDING)
                .date(LocalDate.of(2026, 6, 9))
                .fundingId("fund-1")
                .fundingHash("ftx-1")
                .fundingEntity("Funding Entity")
                .amount(new BigDecimal("123.45"))
                .currency(usd())
                .projectAllocations(List.of(allocation("proj-1", "Project One", null, List.of())))
                .items(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertEquals("event-1", entity.getEventId());
        assertEquals(EventType.FUNDING, entity.getEventType());
        assertEquals(LocalDate.of(2026, 6, 9), entity.getEventDate());
        assertEquals("fund-1", entity.getFundingId());
        assertEquals("ftx-1", entity.getFundingTx());
        assertEquals("Funding Entity", entity.getFundingEntity());
        assertEquals(new BigDecimal("123.45"), entity.getTotalAmount());
        assertEquals("USD", entity.getCurrency());
        assertEquals("ISO_4217:USD", entity.getCurrencyId());
        assertEquals("org1", entity.getOrganisationId());
        assertEquals(Optional.of(BlockchainPublishStatus.STORED), entity.getL1SubmissionData().flatMap(d -> d.getPublishStatus()));
    }

    @Test
    void convertsItemsAndAllocationsWithBacklink() {
        stubOrganisation();

        SpendingEventPublishView.SpendItem item = SpendingEventPublishView.SpendItem.builder()
                .itemId("item-1").category("Personnel").vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00")).currency(usd())
                .fxRate(new BigDecimal("0.85")).amountRcy(new BigDecimal("85.00"))
                .spendDate(LocalDate.of(2025, 4, 3)).documentHash("hash-1").notes("note")
                .build();

        SpendingEventPublishView.Milestone milestone = SpendingEventPublishView.Milestone.builder()
                .milestoneUid("ms-uid-1").milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("60.00")).allocatedAmount(new BigDecimal("50.00"))
                .currency(usd()).milestoneDate(LocalDate.of(2025, 6, 30))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .date(LocalDate.of(2026, 6, 9)).fundingId("fund-1")
                .amount(new BigDecimal("100.00")).currency(usd())
                .projectAllocations(List.of(allocation("proj-1", "Project One", "Sub One", List.of(milestone))))
                .items(List.of(item))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertEquals(1, entity.getSpendingItems().size());
        SpendingItemEntity itemEntity = entity.getSpendingItems().get(0);
        assertEquals("item-1", itemEntity.getItemId());
        assertEquals("Personnel", itemEntity.getCategory());
        assertEquals("Vendor AB", itemEntity.getVendor());
        assertEquals(new BigDecimal("100.00"), itemEntity.getAmountFcy());
        assertEquals(new BigDecimal("85.00"), itemEntity.getAmountRcy());
        assertEquals("USD", itemEntity.getCurrency());
        assertEquals("ISO_4217:USD", itemEntity.getCurrencyId());
        assertEquals(new BigDecimal("0.85"), itemEntity.getFxRate());
        assertEquals("hash-1", itemEntity.getDocumentHash());
        assertEquals(entity, itemEntity.getEvent());

        assertEquals(1, entity.getProjectAllocations().size());
        EventProjectAllocationEntity allocationEntity = entity.getProjectAllocations().get(0);
        assertEquals("proj-1", allocationEntity.getProjectId());
        assertEquals("Project One", allocationEntity.getProjectTitle());
        assertEquals("Sub One", allocationEntity.getSubProjectTitle());
        assertEquals(entity, allocationEntity.getEvent());

        assertEquals(1, allocationEntity.getMilestones().size());
        EventMilestoneAllocationEntity milestoneEntity = allocationEntity.getMilestones().get(0);
        assertEquals("ms-uid-1", milestoneEntity.getMilestoneId());
        assertEquals("Milestone AB", milestoneEntity.getMilestoneTitle());
        assertEquals(new BigDecimal("60.00"), milestoneEntity.getAmountRcy());
        assertEquals(allocationEntity, milestoneEntity.getAllocation());
    }

    @Test
    void preservesMultipleProjectAllocations() {
        stubOrganisation();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.FUNDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(List.of(
                        allocation("proj-1", "Project One", null, List.of()),
                        allocation("proj-2", "Project Two", null, List.of())))
                .items(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getProjectAllocations()).hasSize(2);
        assertThat(entity.getProjectAllocations()).extracting(EventProjectAllocationEntity::getProjectId)
                .containsExactly("proj-1", "proj-2");
    }

    @Test
    void fundingItem_lightFieldsArePreserved() {
        stubOrganisation();

        // FUNDING/REFUND items only carry the reporting-currency amount.
        SpendingEventPublishView.SpendItem item = SpendingEventPublishView.SpendItem.builder()
                .itemId("item-1").amountRcy(new BigDecimal("100.00")).currency(usd())
                .spendDate(LocalDate.of(2026, 6, 11))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.FUNDING)
                .currency(usd()).amount(new BigDecimal("100.00"))
                .projectAllocations(List.of())
                .items(List.of(item))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        SpendingItemEntity itemEntity = entity.getSpendingItems().get(0);
        assertEquals(new BigDecimal("100.00"), itemEntity.getAmountRcy());
        assertThat(itemEntity.getCategory()).isNull();
        assertThat(itemEntity.getVendor()).isNull();
        assertThat(itemEntity.getAmountFcy()).isNull();
        assertThat(itemEntity.getFxRate()).isNull();
    }

    @Test
    void nullItemsList_returnsEmptySpendingItems() {
        stubOrganisation();
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(List.of())
                .items(null)
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getSpendingItems()).isEmpty();
    }

    @Test
    void nullProjectAllocations_returnsEmptyAllocations() {
        stubOrganisation();
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(null)
                .items(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getProjectAllocations()).isEmpty();
    }

    @Test
    void nullAmount_totalAmountIsZero() {
        stubOrganisation();
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .currency(usd()).amount(null)
                .projectAllocations(List.of()).items(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void nullCurrency_nullCustCodeAndCurrencyId() {
        stubOrganisation();
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .currency(null).amount(BigDecimal.ONE)
                .projectAllocations(List.of()).items(List.of())
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getCurrency()).isNull();
        assertThat(entity.getCurrencyId()).isNull();
    }

    @Test
    void orgNotFound_throwsIllegalStateException() {
        when(organisationPublicApi.findByOrganisationId("unknown")).thenReturn(Optional.empty());
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("unknown").eventType(EventType.SPENDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(List.of()).items(List.of())
                .build();

        assertThatThrownBy(() -> converter.convertToDbDetached("unknown", view))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

}
