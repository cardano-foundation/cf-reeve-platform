package org.cardanofoundation.lob.app.funding.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Cross-field / relational business validations for the funding domain. These need the resolved
 * project / milestone entities (not just the request), so they live in the service layer rather
 * than as bean-validation annotations. Each method returns an empty {@link Optional} when valid, or
 * a {@link ProblemDetail} (400) describing the first violation otherwise.
 */
public final class FundingValidations {

    private FundingValidations() {
    }

    /**
     * Validates a milestone's amount and date against its project. {@code amount} and {@code date}
     * are the effective values (a null value is left unchecked, so this also serves partial updates).
     * {@code otherMilestonesTotal} is the summed amount of the project's <em>other</em> milestones
     * (excluding the one being created/updated) and drives the cumulative-budget check. Amount checks
     * are skipped for projects without a budget (sub-projects, whose {@code totalAmount} is null).
     */
    public static Optional<ProblemDetail> milestone(BigDecimal amount, LocalDate date,
            ProjectEntity project, BigDecimal otherMilestonesTotal) {
        if (date != null && date.isBefore(LocalDate.now())) {
            return Optional.of(Problems.badRequest(
                    "Milestone date must not be in the past: %s".formatted(date),
                    ErrorTitleConstants.MILESTONE_DATE_IN_PAST));
        }
        if (amount != null && amount.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "Milestone amount must be greater than zero",
                    ErrorTitleConstants.MILESTONE_AMOUNT_INVALID));
        }
        if (project.getTotalAmount() != null && amount != null) {
            if (amount.compareTo(project.getTotalAmount()) > 0) {
                return Optional.of(Problems.badRequest(
                        "Milestone amount %s exceeds the project total %s".formatted(amount, project.getTotalAmount()),
                        ErrorTitleConstants.MILESTONE_AMOUNT_EXCEEDS_PROJECT));
            }
            BigDecimal cumulative = otherMilestonesTotal.add(amount);
            if (cumulative.compareTo(project.getTotalAmount()) > 0) {
                return Optional.of(Problems.badRequest(
                        "Milestones total %s exceeds the project total %s".formatted(cumulative, project.getTotalAmount()),
                        ErrorTitleConstants.MILESTONE_TOTAL_EXCEEDS_PROJECT));
            }
        }
        return Optional.empty();
    }

    /**
     * Per-allocation validation: an amount is required for FUNDING/REFUND events, must be positive,
     * and may not exceed its milestone's amount.
     */
    public static Optional<ProblemDetail> allocation(BigDecimal allocatedAmount, MilestoneEntity milestone, EventType eventType) {
        if (allocatedAmount == null) {
            if (eventType == EventType.FUNDING || eventType == EventType.REFUND) {
                return Optional.of(Problems.badRequest(
                        "allocatedAmount is required for %s events".formatted(eventType),
                        ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED));
            }
            return Optional.empty();
        }
        if (allocatedAmount.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "allocatedAmount must be greater than zero",
                    ErrorTitleConstants.ALLOCATION_AMOUNT_INVALID));
        }
        if (milestone.getMilestoneAmount() != null && allocatedAmount.compareTo(milestone.getMilestoneAmount()) > 0) {
            return Optional.of(Problems.badRequest(
                    "allocatedAmount %s exceeds the milestone amount %s".formatted(allocatedAmount, milestone.getMilestoneAmount()),
                    ErrorTitleConstants.ALLOCATION_EXCEEDS_MILESTONE));
        }
        return Optional.empty();
    }

    /**
     * A FUNDING/REFUND event's total is derived from its milestone allocations, so it must end up
     * strictly positive — guarding against an event whose allocations are absent or sum to zero
     * (e.g. when no milestones were supplied). SPENDING events derive their total from line items and
     * are not constrained here.
     */
    public static Optional<ProblemDetail> eventTotal(EventType eventType, BigDecimal totalAmount) {
        if ((eventType == EventType.FUNDING || eventType == EventType.REFUND)
                && (totalAmount == null || totalAmount.signum() <= 0)) {
            return Optional.of(Problems.badRequest(
                    "%s event total must be greater than zero; allocate a positive amount to at least one milestone".formatted(eventType),
                    ErrorTitleConstants.EVENT_AMOUNT_INVALID));
        }
        return Optional.empty();
    }

    /** The sum of an event's allocations to a single project may not exceed that project's total. */
    public static Optional<ProblemDetail> allocationTotal(BigDecimal projectAllocatedTotal, ProjectEntity project) {
        if (project.getTotalAmount() != null && projectAllocatedTotal.compareTo(project.getTotalAmount()) > 0) {
            return Optional.of(Problems.badRequest(
                    "Allocated total %s exceeds the project total %s".formatted(projectAllocatedTotal, project.getTotalAmount()),
                    ErrorTitleConstants.ALLOCATION_TOTAL_EXCEEDS_PROJECT));
        }
        return Optional.empty();
    }

    /**
     * Validates a project's budget when it is attached under a parent as a sub-project: the
     * sub-project's total may not exceed the parent's total, and the parent's sub-projects' totals
     * may not sum to more than the parent's total. {@code otherSubProjectsTotal} is the summed total
     * of the parent's <em>other</em> sub-projects (excluding the one being attached). Checks are
     * skipped when either budget is absent (a parent or sub-project without a {@code totalAmount}).
     */
    public static Optional<ProblemDetail> subProjectAmount(BigDecimal childTotal, ProjectEntity parent, BigDecimal otherSubProjectsTotal) {
        if (parent.getTotalAmount() == null || childTotal == null) {
            return Optional.empty();
        }
        if (childTotal.compareTo(parent.getTotalAmount()) > 0) {
            return Optional.of(Problems.badRequest(
                    "Sub-project total %s exceeds the parent project total %s".formatted(childTotal, parent.getTotalAmount()),
                    ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT));
        }
        BigDecimal cumulative = otherSubProjectsTotal.add(childTotal);
        if (cumulative.compareTo(parent.getTotalAmount()) > 0) {
            return Optional.of(Problems.badRequest(
                    "Sub-projects total %s exceeds the parent project total %s".formatted(cumulative, parent.getTotalAmount()),
                    ErrorTitleConstants.SUBPROJECT_TOTAL_EXCEEDS_PARENT));
        }
        return Optional.empty();
    }

    /** Sums project total amounts, optionally excluding one project (by id) and ignoring null totals. */
    public static BigDecimal sumProjectTotals(Collection<ProjectEntity> projects, String excludeId) {
        return projects.stream()
                .filter(p -> excludeId == null || !excludeId.equals(p.getId()))
                .map(ProjectEntity::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A project's total budget, when supplied, must be strictly positive. */
    public static Optional<ProblemDetail> projectAmount(BigDecimal totalAmount) {
        if (totalAmount != null && totalAmount.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "Project total amount must be greater than zero",
                    ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        return Optional.empty();
    }

    /** Sums milestone amounts, optionally excluding one milestone (by id) and ignoring null amounts. */
    public static BigDecimal sumMilestoneAmounts(Collection<MilestoneEntity> milestones, String excludeId) {
        return milestones.stream()
                .filter(m -> excludeId == null || !excludeId.equals(m.getId()))
                .map(MilestoneEntity::getMilestoneAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
