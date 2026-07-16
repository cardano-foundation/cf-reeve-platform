package org.cardanofoundation.lob.app.funding.domain.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.annotation.Nullable;

import lombok.*;

import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Communication object published by the funding module to the blockchain publisher. Carries exactly
 * the fields needed to build a schema-valid {@code FUNDING} Cardano metadata record, including
 * multi-project allocations, structured currency objects, milestones and the event's spend detail.
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
    /** Event date; applies to all event types. For a SPENDING event this is the spend date. */
    @Nullable
    private LocalDate eventDate;

    private String fundingId;
    @Nullable
    private String fundingHash;
    @Nullable
    private String fundingEntity;

    private BigDecimal amount;
    private Currency currencyRcy;

    // --- Spend detail: the event's single spend record. SPENDING events only. ---
    @Nullable
    private String category;
    @Nullable
    private String vendor;
    @Nullable
    private BigDecimal amountFcy;
    @Nullable
    private Currency currencyFcy;
    @Nullable
    private BigDecimal fxRate;
    @Nullable
    private BigDecimal amountRcy;
    @Nullable
    private String documentHash;
    @Nullable
    private String notes;

    /** One entry per project this event is allocated to. */
    private List<ProjectAllocation> projectAllocations;

    // -------------------------------------------------------------------------

    /**
     * One project this event is allocated to. Exactly one of two shapes, so it is unambiguous where
     * the money is booked:
     * <ul>
     *   <li>direct allocation — {@code externalProjectId}/{@code projectTitle} plus {@code milestones};</li>
     *   <li>sub-project allocation — {@code externalProjectId}/{@code projectTitle} carry the root
     *       project, and {@code subProject} carries the actually-allocated sub-project with
     *       <em>its</em> id, title and milestones ({@code milestones} is null at this level).</li>
     * </ul>
     */
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ProjectAllocation {
        /** User-defined id of the root project (the top-level ancestor for sub-project allocations). */
        private String externalProjectId;
        /** Title of the root project. */
        @Nullable
        private String projectTitle;
        /** The allocated sub-project — set only when the allocation targets one; then {@code milestones} is null. */
        @Nullable
        private SubProject subProject;
        /** Milestones of a direct project allocation; null when the allocation targets a sub-project. */
        @Nullable
        private List<Milestone> milestones;
    }

    /** The sub-project an allocation targets, carrying its own id, title and milestone allocations. */
    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SubProject {
        /** User-defined id of the sub-project. */
        private String subProjectId;
        @Nullable
        private String subProjectTitle;
        private List<Milestone> milestones;
    }

    @Getter
    @Setter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Milestone {
        private String milestoneId;
        private String milestoneTitle;
        @Nullable
        private BigDecimal milestoneAmount;
        @Nullable
        private BigDecimal allocatedAmount;
        private Currency currency;
        private LocalDate milestoneDate;
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
