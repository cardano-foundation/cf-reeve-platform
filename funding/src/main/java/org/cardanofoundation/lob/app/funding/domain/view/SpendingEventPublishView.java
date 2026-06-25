package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.*;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Communication object published by the funding module to the blockchain publisher. Carries exactly
 * the fields needed to build a schema-valid {@code EVENT_BUNDLE} Cardano metadata record, including
 * multi-project allocations, structured currency objects, milestones and spend items.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Setter
public class SpendingEventPublishView {

    private String eventId;
    private String organisationId;
    private EventType eventType;
    private LocalDate date;

    private String fundingId;
    @Nullable
    private String fundingTx;

    private BigDecimal amount;
    private Currency currency;

    /** One entry per project this event is allocated to (FUNDING / REFUND / SPENDING context). */
    private List<ProjectAllocation> projectAllocations;

    /** Spend items — populated for SPENDING events only. */
    private List<SpendItem> items;

    // -------------------------------------------------------------------------

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ProjectAllocation {
        private String projectId;
        private String activityId;
        @Nullable
        private String activityTitle;
        @Nullable
        private String activitySubId;
        private List<Milestone> milestones;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Milestone {
        private String milestoneId;
        private String milestoneLabel;
        @Nullable
        private BigDecimal expectedCost;
        @Nullable
        private BigDecimal allocatedAmount;
        private Currency currency;
        private LocalDate dueDate;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SpendItem {
        private String itemId;
        private String category;
        private String vendor;
        private BigDecimal amountFcy;
        private Currency currency;
        private BigDecimal fxRate;
        @Nullable
        private BigDecimal amountRcy;
        private LocalDate spendDate;
        @Nullable
        private String documentHash;
        @Nullable
        private String notes;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Currency {
        /** ISO id, e.g. {@code ISO_4217:USD}. */
        private String id;
        /** Short code, e.g. {@code USD}. */
        private String custCode;
    }

}
