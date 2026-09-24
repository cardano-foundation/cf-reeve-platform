package org.cardanofoundation.lob.app.funding.domain.enums;

public enum EventStatus {
    DRAFT,
    PUBLISHED,
    /**
     * A DRAFT event whose allocation no longer reconciles against its milestone's own budgeted amount,
     * because a milestone-amount shrink was allowed through instead of being rejected outright
     * (LOB-2365). Concretely: {@code MilestoneService#update} allows a milestone's own amount to shrink
     * below what's already allocated to it (see {@code FundingValidations#milestoneCoversAllocations})
     * — real recorded money can't be un-recorded, so blocking the edit isn't right — the edit proceeds
     * exactly as typed (no event's own allocated figure is ever rewritten), and every DRAFT event fully
     * allocated to that milestone is set to {@code ERROR} instead
     * (see {@code FundingCascadeDeleteService#markContainedEventsAsErrorOrBlock}), so a human has to go
     * into the event and correct its allocations before it can ever be published — this status is
     * deliberately not auto-corrected.
     *
     * <p>This is distinct from a project's total no longer covering its milestones'/sub-projects' own
     * *declared* budgets — that's two budget declarations disagreeing with each other rather than a
     * budget disagreeing with real recorded money, and is a hard reject at
     * {@code ProjectTreeUpdateService#updateWithMilestones} (see
     * {@code FundingValidations#projectTotalCoversChildren}), not a path to this status (LOB-2365
     * follow-up).
     *
     * <p>A {@code PUBLISHED} event can never reach this state: LOB-2365's lock rules (a milestone with a
     * published allocation is field-locked project-wide the moment any published event exists anywhere
     * in its structure) mean the amount-shrink relaxation above only ever applies while every event in
     * scope is still {@code DRAFT}.
     */
    ERROR
}
