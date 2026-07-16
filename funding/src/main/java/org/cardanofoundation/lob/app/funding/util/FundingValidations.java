package org.cardanofoundation.lob.app.funding.util;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
     * Returns the first value that occurs more than once (case-sensitive), ignoring nulls. Used to
     * reject duplicate sibling titles inside a single create request up front — before any entity is
     * persisted — so same-request duplicates can't slip past a per-row database check.
     */
    public static Optional<String> firstDuplicate(List<String> values) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value != null && !seen.add(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Validates a milestone's amount against its project. {@code amount} is the effective value
     * (a null value is left unchecked, so this also serves partial updates).
     * {@code otherMilestonesTotal} is the summed amount of the project's <em>other</em> milestones
     * (excluding the one being created/updated) and drives the cumulative-budget check. Amount checks
     * are skipped for projects without a budget (sub-projects, whose {@code totalAmount} is null).
     */
    public static Optional<ProblemDetail> milestone(BigDecimal amount,
            ProjectEntity project, BigDecimal otherMilestonesTotal) {
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
     * When a milestone's amount is changed, it may not drop below the total already allocated to it
     * by events — a milestone must always be able to cover its outstanding allocations. Skipped when
     * no new amount is supplied.
     */
    public static Optional<ProblemDetail> milestoneCoversAllocations(BigDecimal newAmount, BigDecimal totalAllocated) {
        if (newAmount != null && totalAllocated != null && newAmount.compareTo(totalAllocated) < 0) {
            return Optional.of(Problems.badRequest(
                    "Milestone amount %s is below the total already allocated to it %s".formatted(newAmount, totalAllocated),
                    ErrorTitleConstants.MILESTONE_AMOUNT_BELOW_ALLOCATED));
        }
        return Optional.empty();
    }

    /**
     * Per-allocation validation: an amount is always required (the event total is the sum of the
     * allocations), must be positive, and may not exceed its milestone's amount.
     */
    public static Optional<ProblemDetail> allocation(BigDecimal allocatedAmount, MilestoneEntity milestone, EventType eventType) {
        if (allocatedAmount == null) {
            return Optional.of(Problems.badRequest(
                    "allocatedAmount is required",
                    ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED));
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
     * An event's total is the sum of its milestone allocations, so it must end up strictly positive —
     * guarding against an event whose allocations are absent or sum to zero (e.g. when no milestones
     * were supplied).
     */
    public static Optional<ProblemDetail> eventTotal(EventType eventType, BigDecimal totalAmount) {
        if (totalAmount == null || totalAmount.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "%s event total must be greater than zero; allocate a positive amount to at least one milestone".formatted(eventType),
                    ErrorTitleConstants.EVENT_AMOUNT_INVALID));
        }
        return Optional.empty();
    }

    private static final BigDecimal FX_TOLERANCE = new BigDecimal("0.01");

    /**
     * Validates the spend detail on a milestone allocation. Spend fields are only permitted for
     * SPENDING events; for SPENDING they are required and must be internally consistent
     * ({@code amountFcy = amountRcy * fxRate}) with the allocated amount not exceeding the
     * reporting-currency spend ({@code amountRcy}). The event date is a general field (all event
     * types) and is validated separately, not here.
     */
    public static Optional<ProblemDetail> spendDetail(
            EventType eventType,
            String category, String vendor,
            BigDecimal amountFcy, String currencyFcy, BigDecimal fxRate, BigDecimal amountRcy, String currencyRcy,
            String hash, String notes) {

        boolean anySpendField = category != null || vendor != null || amountFcy != null || currencyFcy != null
                || fxRate != null || amountRcy != null || hash != null || notes != null;

        if (eventType != EventType.SPENDING) {
            if (anySpendField) {
                return Optional.of(Problems.badRequest(
                        "Spend detail (amounts, vendor, fxRate, ...) is only allowed for SPENDING events",
                        ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED));
            }
            return Optional.empty();
        }

        // SPENDING: the amount fields are required to record the spend.
        if (amountFcy == null || amountRcy == null || fxRate == null || currencyRcy == null || currencyFcy == null) {
            return Optional.of(Problems.badRequest(
                    "amountFcy, amountRcy, currencyRcy, currencyFcy and fxRate are required for SPENDING events",
                    ErrorTitleConstants.SPEND_FIELDS_REQUIRED));
        }

        return Optional.empty();
    }

    /**
     * A SPENDING event's spend ({@code amountRcy}) must be fully allocated: the milestone allocations
     * must sum to exactly the spent amount — no more (you can't allocate what wasn't spent) and no
     * less (every spent unit must be booked against a milestone).
     */
    public static Optional<ProblemDetail> spendFullyAllocated(EventType eventType, BigDecimal totalAllocated, BigDecimal amountRcy) {
        if (eventType != EventType.SPENDING || amountRcy == null || totalAllocated == null) {
            return Optional.empty();
        }
        if (totalAllocated.compareTo(amountRcy) > 0) {
            return Optional.of(Problems.badRequest(
                    "Allocated total %s exceeds the event's spent amount (amountRcy) %s".formatted(totalAllocated, amountRcy),
                    ErrorTitleConstants.ALLOCATION_EXCEEDS_SPEND));
        }
        if (totalAllocated.compareTo(amountRcy) < 0) {
            return Optional.of(Problems.badRequest(
                    "Allocated total %s does not fully allocate the event's spent amount (amountRcy) %s"
                            .formatted(totalAllocated, amountRcy),
                    ErrorTitleConstants.SPEND_NOT_FULLY_ALLOCATED));
        }
        return Optional.empty();
    }

    /**
     * A SPENDING event's spend ({@code amountRcy}) may not exceed the combined budget of the milestones
     * it is booked against, nor the combined budget of the projects it is assigned to. A null budget
     * (passed as {@code null}) lifts that bound — it cannot be meaningfully enforced.
     */
    public static Optional<ProblemDetail> eventAmountWithinBudget(EventType eventType, BigDecimal amountRcy,
            BigDecimal summedMilestoneBudget, BigDecimal summedProjectBudget) {
        if (eventType != EventType.SPENDING || amountRcy == null) {
            return Optional.empty();
        }
        if (summedMilestoneBudget != null && amountRcy.compareTo(summedMilestoneBudget) > 0) {
            return Optional.of(Problems.badRequest(
                    "Event amount %s exceeds the total milestone budget %s".formatted(amountRcy, summedMilestoneBudget),
                    ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_MILESTONES));
        }
        if (summedProjectBudget != null && amountRcy.compareTo(summedProjectBudget) > 0) {
            return Optional.of(Problems.badRequest(
                    "Event amount %s exceeds the total project budget %s".formatted(amountRcy, summedProjectBudget),
                    ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_PROJECT));
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

    /**
     * A project node in a request holds either milestones or sub-projects, never both. Applied to
     * every node of a create-project or event-allocation tree.
     */
    public static Optional<ProblemDetail> milestonesXorSubProjects(boolean hasMilestones, boolean hasSubProjects) {
        if (hasMilestones && hasSubProjects) {
            return Optional.of(Problems.badRequest(
                    "A project has either milestones or sub-projects, not both",
                    ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES));
        }
        return Optional.empty();
    }

    /**
     * A project node holds either milestones or sub-projects, never both. Rejects adding a milestone
     * to a project that already has sub-projects.
     */
    public static Optional<ProblemDetail> milestoneAllowed(boolean projectHasSubProjects) {
        if (projectHasSubProjects) {
            return Optional.of(Problems.badRequest(
                    "Cannot add a milestone to a project that has sub-projects; a project has either milestones or sub-projects, not both",
                    ErrorTitleConstants.MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS));
        }
        return Optional.empty();
    }

    /**
     * A project node holds either milestones or sub-projects, never both. Rejects adding a sub-project
     * under a parent that already has milestones.
     */
    public static Optional<ProblemDetail> subProjectAllowed(boolean parentHasMilestones) {
        if (parentHasMilestones) {
            return Optional.of(Problems.badRequest(
                    "Cannot add a sub-project to a project that has milestones; a project has either milestones or sub-projects, not both",
                    ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES));
        }
        return Optional.empty();
    }

    /**
     * When a project's total budget is changed, it must still cover what has already been planned
     * under it: the summed amounts of its milestones and the summed totals of its sub-projects.
     * Skipped when no new total is supplied.
     */
    public static Optional<ProblemDetail> projectTotalCoversChildren(BigDecimal newTotal,
            BigDecimal milestonesTotal, BigDecimal subProjectsTotal) {
        if (newTotal == null) {
            return Optional.empty();
        }
        if (milestonesTotal != null && newTotal.compareTo(milestonesTotal) < 0) {
            return Optional.of(Problems.badRequest(
                    "Project total %s is below the summed milestone amounts %s".formatted(newTotal, milestonesTotal),
                    ErrorTitleConstants.PROJECT_AMOUNT_BELOW_MILESTONES));
        }
        if (subProjectsTotal != null && newTotal.compareTo(subProjectsTotal) < 0) {
            return Optional.of(Problems.badRequest(
                    "Project total %s is below the summed sub-project totals %s".formatted(newTotal, subProjectsTotal),
                    ErrorTitleConstants.PROJECT_AMOUNT_BELOW_SUBPROJECTS));
        }
        return Optional.empty();
    }

    /** FUNDING events record who provided the funds — {@code fundingEntity} is required for them. */
    public static Optional<ProblemDetail> fundingEntity(EventType eventType, String fundingEntity) {
        if (eventType == EventType.FUNDING && (fundingEntity == null || fundingEntity.isBlank())) {
            return Optional.of(Problems.badRequest(
                    "fundingEntity is required for FUNDING events",
                    ErrorTitleConstants.FUNDING_ENTITY_REQUIRED));
        }
        return Optional.empty();
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
