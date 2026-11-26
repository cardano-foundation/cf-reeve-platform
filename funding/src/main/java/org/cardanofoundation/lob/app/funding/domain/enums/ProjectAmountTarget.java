package org.cardanofoundation.lob.app.funding.domain.enums;

/**
 * The root-project amount the projects-list amount range filter compares against — the same values the
 * Projects table shows in its Budgeted / Funded / Spent columns.
 */
public enum ProjectAmountTarget {
    /** The project's total (budgeted) amount. */
    BUDGETED,
    /** Net funding: allocated FUNDING minus allocated REFUND, rolled up through milestones and sub-projects. */
    FUNDED,
    /** Allocated SPENDING, rolled up through milestones and sub-projects. */
    SPENT
}
