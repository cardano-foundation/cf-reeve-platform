package org.cardanofoundation.lob.app.funding.domain.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventPublishView;

/**
 * Verifies the funding → blockchain-publisher event ({@link SpendingEventsPublishCommand} and its nested
 * {@link SpendingEventPublishView} graph) survives a JSON serialize/deserialize round trip.
 */
class SpendingEventsPublishCommandSerdeTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void roundTrips_spendingEventWithItems() throws Exception {
        SpendingEventPublishView.Currency usd = SpendingEventPublishView.Currency.builder()
                .id("ISO_4217:USD").custCode("USD").build();

        SpendingEventPublishView.SpendItem item = SpendingEventPublishView.SpendItem.builder()
                .itemId("item-1")
                .category("Personnel")
                .vendor("Vendor AB")
                .amountFcy(new BigDecimal("100.00"))
                .currency(usd)
                .fxRate(new BigDecimal("0.85"))
                .amountRcy(new BigDecimal("85.00"))
                .spendDate(LocalDate.of(2025, 4, 3))
                .documentHash("doc-hash-1")
                .notes("Invoice #1")
                .build();

        SpendingEventPublishView.ProjectAllocation allocation = SpendingEventPublishView.ProjectAllocation.builder()
                .externalProjectId("PROJ-AB")
                .projectTitle("Project One")
                .subProjectTitle("Sub Project One")
                .milestones(List.of())
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-1")
                .organisationId("org-1")
                .eventType(EventType.SPENDING)
                .date(LocalDate.of(2025, 4, 30))
                .fundingId("fund-1")
                .fundingHash("ftx-1")
                .amount(new BigDecimal("100.00"))
                .currency(usd)
                .projectAllocations(List.of(allocation))
                .items(List.of(item))
                .build();

        SpendingEventsPublishCommand command = new SpendingEventsPublishCommand("org-1", Set.of(view));

        String json = objectMapper.writeValueAsString(command);
        SpendingEventsPublishCommand result = objectMapper.readValue(json, SpendingEventsPublishCommand.class);

        assertThat(result.getOrganisationId()).isEqualTo("org-1");
        assertThat(result.getSpendingEvents()).hasSize(1);

        SpendingEventPublishView resultView = result.getSpendingEvents().iterator().next();
        assertThat(resultView.getEventId()).isEqualTo("event-1");
        assertThat(resultView.getEventType()).isEqualTo(EventType.SPENDING);
        assertThat(resultView.getDate()).isEqualTo(LocalDate.of(2025, 4, 30));
        assertThat(resultView.getCurrency().getId()).isEqualTo("ISO_4217:USD");
        assertThat(resultView.getCurrency().getCustCode()).isEqualTo("USD");
        assertThat(resultView.getProjectAllocations()).hasSize(1);
        assertThat(resultView.getProjectAllocations().get(0).getExternalProjectId()).isEqualTo("PROJ-AB");
        assertThat(resultView.getProjectAllocations().get(0).getProjectTitle()).isEqualTo("Project One");
        assertThat(resultView.getProjectAllocations().get(0).getSubProjectTitle()).isEqualTo("Sub Project One");

        assertThat(resultView.getItems()).hasSize(1);
        SpendingEventPublishView.SpendItem resultItem = resultView.getItems().get(0);
        assertThat(resultItem.getItemId()).isEqualTo("item-1");
        assertThat(resultItem.getAmountFcy()).isEqualByComparingTo("100.00");
        assertThat(resultItem.getSpendDate()).isEqualTo(LocalDate.of(2025, 4, 3));
        assertThat(resultItem.getCurrency().getCustCode()).isEqualTo("USD");
    }

    @Test
    void roundTrips_fundingEventWithMilestones() throws Exception {
        SpendingEventPublishView.Currency usd = SpendingEventPublishView.Currency.builder()
                .id("ISO_4217:USD").custCode("USD").build();

        SpendingEventPublishView.Milestone milestone = SpendingEventPublishView.Milestone.builder()
                .milestoneId("ms-uid-1")
                .milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("60.00"))
                .allocatedAmount(new BigDecimal("50.00"))
                .currency(usd)
                .milestoneDate(LocalDate.of(2025, 6, 30))
                .build();

        SpendingEventPublishView.ProjectAllocation allocation = SpendingEventPublishView.ProjectAllocation.builder()
                .externalProjectId("PROJ-AB")
                .projectTitle("Project One")
                .milestones(List.of(milestone))
                .build();

        SpendingEventPublishView view = SpendingEventPublishView.builder()
                .eventId("event-2")
                .organisationId("org-1")
                .eventType(EventType.FUNDING)
                .date(LocalDate.of(2025, 1, 15))
                .fundingId("fund-1")
                .amount(new BigDecimal("50.00"))
                .currency(usd)
                .projectAllocations(List.of(allocation))
                .items(List.of())
                .build();

        SpendingEventsPublishCommand command = new SpendingEventsPublishCommand("org-1", Set.of(view));

        String json = objectMapper.writeValueAsString(command);
        SpendingEventsPublishCommand result = objectMapper.readValue(json, SpendingEventsPublishCommand.class);

        SpendingEventPublishView resultView = result.getSpendingEvents().iterator().next();
        assertThat(resultView.getProjectAllocations()).hasSize(1);
        SpendingEventPublishView.ProjectAllocation resultAlloc = resultView.getProjectAllocations().get(0);
        assertThat(resultAlloc.getMilestones()).hasSize(1);
        SpendingEventPublishView.Milestone resultMilestone = resultAlloc.getMilestones().get(0);
        assertThat(resultMilestone.getMilestoneId()).isEqualTo("ms-uid-1");
        assertThat(resultMilestone.getMilestoneAmount()).isEqualByComparingTo("60.00");
        assertThat(resultMilestone.getAllocatedAmount()).isEqualByComparingTo("50.00");
        assertThat(resultMilestone.getMilestoneDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(resultMilestone.getCurrency().getId()).isEqualTo("ISO_4217:USD");
    }

}
