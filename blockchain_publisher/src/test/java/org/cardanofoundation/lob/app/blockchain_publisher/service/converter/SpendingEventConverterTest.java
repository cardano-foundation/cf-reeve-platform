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

    private SpendingEventPublishView.Currency eur() {
        return SpendingEventPublishView.Currency.builder().id("ISO_4217:EUR").custCode("EUR").build();
    }

    /** A direct project allocation — milestones at the project level, no sub-project. */
    private SpendingEventPublishView.ProjectAllocation allocation(String projectId, String projectTitle,
                                                                  List<SpendingEventPublishView.Milestone> milestones) {
        return SpendingEventPublishView.ProjectAllocation.builder()
                .externalProjectId(projectId).projectTitle(projectTitle)
                .milestones(milestones).build();
    }

    /** A sub-project allocation — the milestones travel nested inside the sub-project object. */
    private SpendingEventPublishView.ProjectAllocation subProjectAllocation(String projectId, String projectTitle,
                                                                            String subProjectId, String subProjectTitle,
                                                                            List<SpendingEventPublishView.Milestone> milestones) {
        return SpendingEventPublishView.ProjectAllocation.builder()
                .externalProjectId(projectId).projectTitle(projectTitle)
                .subProject(SpendingEventPublishView.SubProject.builder()
                        .subProjectId(subProjectId).subProjectTitle(subProjectTitle)
                        .milestones(milestones).build())
                .build();
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
                .projectAllocations(List.of(allocation("proj-1", "Project One", List.of())))
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
    void convertsSpendDetailOntoEventWithMilestoneBacklink() {
        stubOrganisation();

        SpendingEventPublishView.Milestone milestone = SpendingEventPublishView.Milestone.builder()
                .milestoneId("ms-uid-1").milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("60.00")).allocatedAmount(new BigDecimal("50.00"))
                .currency(usd()).milestoneDate(LocalDate.of(2025, 6, 30))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .date(LocalDate.of(2026, 6, 9)).fundingId("fund-1")
                .amount(new BigDecimal("50.00")).currency(usd())
                // spend detail — event level
                .category("Personnel").vendor("Vendor AB").amountFcy(new BigDecimal("100.00"))
                .spendCurrency(eur()).fxRate(new BigDecimal("0.85")).amountRcy(new BigDecimal("85.00"))
                .spendDate(LocalDate.of(2025, 4, 3)).documentHash("hash-1").notes("note")
                .projectAllocations(List.of(subProjectAllocation("proj-1", "Project One", "sub-1", "Sub One", List.of(milestone))))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertEquals("Personnel", entity.getCategory());
        assertEquals("Vendor AB", entity.getVendor());
        assertEquals(new BigDecimal("100.00"), entity.getAmountFcy());
        assertEquals(new BigDecimal("85.00"), entity.getAmountRcy());
        assertEquals("EUR", entity.getSpendCurrency());
        assertEquals("ISO_4217:EUR", entity.getSpendCurrencyId());
        assertEquals(new BigDecimal("0.85"), entity.getFxRate());
        assertEquals("hash-1", entity.getDocumentHash());

        assertEquals(1, entity.getProjectAllocations().size());
        EventProjectAllocationEntity allocationEntity = entity.getProjectAllocations().get(0);
        assertEquals("proj-1", allocationEntity.getProjectId());
        assertEquals("sub-1", allocationEntity.getSubProjectId());
        assertEquals("Sub One", allocationEntity.getSubProjectTitle());
        assertEquals(entity, allocationEntity.getEvent());

        assertEquals(1, allocationEntity.getMilestones().size());
        EventMilestoneAllocationEntity ms = allocationEntity.getMilestones().get(0);
        assertEquals("ms-uid-1", ms.getMilestoneId());
        assertEquals("Milestone AB", ms.getMilestoneTitle());
        assertEquals(new BigDecimal("50.00"), ms.getAllocatedAmount());
        assertEquals(allocationEntity, ms.getAllocation());
    }

    @Test
    void fundingEvent_hasNoSpendDetail() {
        stubOrganisation();

        SpendingEventPublishView.Milestone milestone = SpendingEventPublishView.Milestone.builder()
                .milestoneId("ms-1").milestoneTitle("MS").allocatedAmount(new BigDecimal("100.00"))
                .currency(usd()).milestoneDate(LocalDate.of(2026, 6, 11))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.FUNDING)
                .currency(usd()).amount(new BigDecimal("100.00"))
                .projectAllocations(List.of(allocation("proj-1", "Project One", List.of(milestone))))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        EventMilestoneAllocationEntity ms = entity.getProjectAllocations().get(0).getMilestones().get(0);
        assertEquals(new BigDecimal("100.00"), ms.getAllocatedAmount());
        assertThat(entity.getCategory()).isNull();
        assertThat(entity.getVendor()).isNull();
        assertThat(entity.getAmountFcy()).isNull();
        assertThat(entity.getFxRate()).isNull();
    }

    @Test
    void preservesMultipleProjectAllocations() {
        stubOrganisation();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.FUNDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(List.of(
                        allocation("proj-1", "Project One", List.of()),
                        allocation("proj-2", "Project Two", List.of())))
                .build();

        SpendingEventEntity entity = converter.convertToDbDetached("org1", view);

        assertThat(entity.getProjectAllocations()).hasSize(2);
        assertThat(entity.getProjectAllocations()).extracting(EventProjectAllocationEntity::getProjectId)
                .containsExactly("proj-1", "proj-2");
    }

    @Test
    void nullProjectAllocations_returnsEmptyAllocations() {
        stubOrganisation();
        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1").organisationId("org1").eventType(EventType.SPENDING)
                .currency(usd()).amount(BigDecimal.ONE)
                .projectAllocations(null)
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
                .projectAllocations(List.of())
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
                .projectAllocations(List.of())
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
                .projectAllocations(List.of())
                .build();

        assertThatThrownBy(() -> converter.convertToDbDetached("unknown", view))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

}
