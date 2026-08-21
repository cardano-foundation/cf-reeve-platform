package org.cardanofoundation.lob.app.funding.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
     * An event is booked in a single recording currency ({@code currencyRcy}), and a milestone's
     * currency always matches its owning project's (root projects require it explicitly; sub-projects
     * and milestones inherit it — see {@link org.cardanofoundation.lob.app.funding.service.ProjectStructureService}
     * and {@code FundingBulkImportService#upsertMilestoneRow}). So an event allocating to a milestone
     * whose currency differs from the event's would silently record amounts in the wrong currency
     * (e.g. a USD spend booked against a EUR milestone) — this rejects that up front, for both the
     * REST API and CSV import (they share this validation pipeline).
     */
    public static Optional<ProblemDetail> eventCurrencyMatchesMilestone(String eventCurrencyRcy, MilestoneEntity milestone) {
        if (eventCurrencyRcy != null && milestone.getCurrency() != null
                && !eventCurrencyRcy.equals(milestone.getCurrency())) {
            return Optional.of(Problems.badRequest(
                    "currencyRcy %s does not match milestone '%s' currency %s".formatted(
                            eventCurrencyRcy, milestone.getMilestoneTitle(), milestone.getCurrency()),
                    ErrorTitleConstants.EVENT_CURRENCY_MISMATCH));
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
     * Validates the spend detail on a milestone allocation. {@code category}/{@code vendor}/
     * {@code amountFcy}/{@code currencyFcy}/{@code fxRate}/{@code hash}/{@code notes} are only
     * permitted for SPENDING events (where they are also required) — they describe a foreign-currency
     * purchase being reconciled, which FUNDING/REFUND events have no equivalent of. {@code amountRcy}
     * is required for every event type: it's the recorded amount (spent, funded, or refunded) that the
     * milestone allocations must fully cover — see {@link #spendFullyAllocated}. The event date is a
     * general field (all event types) and is validated separately, not here.
     */
    public static Optional<ProblemDetail> spendDetail(
            EventType eventType,
            String category, String vendor,
            BigDecimal amountFcy, String currencyFcy, BigDecimal fxRate, BigDecimal amountRcy, String currencyRcy,
            String hash, String notes) {

        boolean anySpendOnlyField = category != null || vendor != null || amountFcy != null || currencyFcy != null
                || fxRate != null || hash != null || notes != null;

        if (eventType != EventType.SPENDING && anySpendOnlyField) {
            return Optional.of(Problems.badRequest(
                    "Category, vendor, amountFcy, currencyFcy, fxRate, hash and notes are only allowed for SPENDING events",
                    ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED));
        }

        // Name only the fields that are actually missing — a blanket list is misleading when just one is
        // absent. Order matches the field list above (amountFcy, amountRcy, currencyRcy, currencyFcy,
        // fxRate) regardless of which subset applies to eventType.
        List<String> missing = new ArrayList<>();
        if (eventType == EventType.SPENDING && amountFcy == null) missing.add("amountFcy");
        if (amountRcy == null) missing.add("amountRcy");
        if (eventType == EventType.SPENDING) {
            if (currencyRcy == null) missing.add("currencyRcy");
            if (currencyFcy == null) missing.add("currencyFcy");
            if (fxRate == null) missing.add("fxRate");
        }
        if (!missing.isEmpty()) {
            return Optional.of(Problems.badRequest(
                    "Missing required field(s) for a %s event: ".formatted(eventType) + String.join(", ", missing),
                    ErrorTitleConstants.SPEND_FIELDS_REQUIRED));
        }

        return Optional.empty();
    }

    /**
     * An event's recorded amount ({@code amountRcy}) must be fully allocated: the milestone
     * allocations must sum to exactly that amount — no more (you can't allocate what wasn't
     * spent/funded/refunded) and no less (every unit must be booked against a milestone). Applies to
     * every event type; {@code eventType} is only used to phrase the error.
     */
    public static Optional<ProblemDetail> spendFullyAllocated(EventType eventType, BigDecimal totalAllocated, BigDecimal amountRcy) {
        if (amountRcy == null || totalAllocated == null) {
            return Optional.empty();
        }
        if (totalAllocated.compareTo(amountRcy) > 0) {
            return Optional.of(Problems.badRequest(
                    "Allocated total %s exceeds the %s event's amount (amountRcy) %s".formatted(totalAllocated, eventType, amountRcy),
                    ErrorTitleConstants.ALLOCATION_EXCEEDS_SPEND));
        }
        if (totalAllocated.compareTo(amountRcy) < 0) {
            return Optional.of(Problems.badRequest(
                    "Allocated total %s does not fully allocate the %s event's amount (amountRcy) %s"
                            .formatted(totalAllocated, eventType, amountRcy),
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

    /**
     * An event's date (funding date, spending date or refund date, depending on {@code eventType})
     * may not be in the future.
     */
    public static Optional<ProblemDetail> eventDateNotInFuture(LocalDate eventDate) {
        if (eventDate != null && eventDate.isAfter(LocalDate.now())) {
            return Optional.of(Problems.badRequest(
                    "eventDate %s must not be in the future".formatted(eventDate),
                    ErrorTitleConstants.EVENT_DATE_IN_FUTURE));
        }
        return Optional.empty();
    }

    /**
     * Every event must carry a date — applies to all event types (FUNDING, SPENDING, REFUND). A
     * dateless event cannot be placed in a reporting period, so it must be rejected rather than
     * silently persisted without one (this is what the CSV import path did before this check existed,
     * since a blank {@code Event Date} cell parses to {@code null} rather than a parse error).
     */
    public static Optional<ProblemDetail> eventDateRequired(LocalDate eventDate) {
        if (eventDate == null) {
            return Optional.of(Problems.badRequest(
                    "eventDate is required",
                    ErrorTitleConstants.EVENT_DATE_REQUIRED));
        }
        return Optional.empty();
    }

    /**
     * A SPENDING event's {@code amountFcy} and {@code fxRate} describe a real foreign-currency
     * purchase converted at a real rate, so both must be strictly positive when present. (Their
     * presence itself is enforced separately by {@link #spendDetail}; this only guards against a
     * supplied zero/negative value — e.g. a CSV cell containing {@code 0} rather than being left
     * blank.) Not applicable to FUNDING/REFUND events, which carry neither field.
     */
    public static Optional<ProblemDetail> spendAmountsPositive(EventType eventType, BigDecimal amountFcy, BigDecimal fxRate) {
        if (eventType != EventType.SPENDING) {
            return Optional.empty();
        }
        if (amountFcy != null && amountFcy.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "amountFcy must be greater than zero for a SPENDING event",
                    ErrorTitleConstants.AMOUNT_FCY_INVALID));
        }
        if (fxRate != null && fxRate.signum() <= 0) {
            return Optional.of(Problems.badRequest(
                    "fxRate must be greater than zero for a SPENDING event",
                    ErrorTitleConstants.FX_RATE_INVALID));
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
