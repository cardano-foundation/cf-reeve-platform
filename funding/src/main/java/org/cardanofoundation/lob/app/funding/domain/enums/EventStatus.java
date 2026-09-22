package org.cardanofoundation.lob.app.funding.domain.enums;

public enum EventStatus {
    DRAFT,
    PUBLISHED,
    /**
     * A DRAFT event whose allocation(s) no longer reconcile against the project/milestone structure
     * they were made against, because a project-level structural edit was allowed through instead of
     * being rejected outright (LOB-2365). Concretely: {@code ProjectService#updateProject} no longer
     * rejects a project's total amount shrinking below what its children currently claim
     * (see {@code FundingValidations#projectTotalCoversChildren}) — the edit proceeds exactly as typed
     * (no milestone amount, sub-project total, or event allocation is ever rewritten), and every DRAFT
     * event fully contained in that project's subtree is set to {@code ERROR} instead
     * (see {@code FundingCascadeDeleteService#markContainedEventsAsErrorOrBlock}), so a human has to go
     * into the event and correct its allocations to match the new structure before it can ever be
     * published — this status is deliberately not auto-corrected.
     *
     * <p>A {@code PUBLISHED} event can never reach this state: LOB-2365's lock rules (a milestone with
     * a published allocation is field-locked; a project's total amount and currency lock project-wide
     * the moment any published event exists anywhere in its structure) mean the total-amount relaxation
     * above only ever applies while every event in scope is still {@code DRAFT}.
     */
    ERROR
}
