package org.cardanofoundation.lob.app.funding.domain.enums;

/**
 * Aggregate structural lock status for a project, computed from its own milestones and the
 * (recursively computed) status of its sub-projects — see {@code ProjectService#lockStatus}. LOB-2365.
 */
public enum ProjectLockStatus {
    /** No published event anywhere in this project's structure — everything is editable. */
    EDITABLE,
    /**
     * At least one published event exists somewhere in this project's structure, but at least one
     * milestone/sub-project remains unallocated to a published event (including a structurally empty
     * sub-project with no milestones/children of its own yet).
     */
    PARTLY_LOCKED,
    /** Every milestone and structural component in this project is tied to a published event. */
    LOCKED
}
