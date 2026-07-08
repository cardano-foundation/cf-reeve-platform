package org.cardanofoundation.lob.app.funding.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

class FundingValidationsTest {

    private ProjectEntity project(BigDecimal total) {
        return ProjectEntity.builder().id("p1").organisationId("org1").totalAmount(total).currency("USD").build();
    }

    private MilestoneEntity milestone(BigDecimal amount) {
        return MilestoneEntity.builder().id("m1").milestoneAmount(amount).currency("USD").build();
    }

    private String title(Optional<ProblemDetail> p) {
        return p.orElseThrow().getTitle();
    }

    // --- milestone(amount, project, otherTotal) ---

    @Test
    void milestone_valid_returnsEmpty() {
        assertThat(FundingValidations.milestone(new BigDecimal("50000"), project(new BigDecimal("200000")), BigDecimal.ZERO))
                .isEmpty();
    }

    @Test
    void milestone_zeroAmount_isRejected() {
        assertThat(title(FundingValidations.milestone(BigDecimal.ZERO, project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_INVALID);
    }

    @Test
    void milestone_negativeAmount_isRejected() {
        assertThat(title(FundingValidations.milestone(new BigDecimal("-1"), project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_INVALID);
    }

    @Test
    void milestone_amountExceedsProject_isRejected() {
        assertThat(title(FundingValidations.milestone(new BigDecimal("250000"), project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_EXCEEDS_PROJECT);
    }

    @Test
    void milestone_cumulativeExceedsProject_isRejected() {
        // each milestone (50000) is within the project total, but the running sum exceeds it
        assertThat(title(FundingValidations.milestone(new BigDecimal("50000"), project(new BigDecimal("200000")), new BigDecimal("180000"))))
                .isEqualTo(ErrorTitleConstants.MILESTONE_TOTAL_EXCEEDS_PROJECT);
    }

    @Test
    void milestone_amountChecksSkipped_whenProjectHasNoBudget() {
        // sub-projects carry a null totalAmount → amount/cumulative checks don't apply
        assertThat(FundingValidations.milestone(new BigDecimal("999999"), project(null), new BigDecimal("999999")))
                .isEmpty();
    }

    @Test
    void milestone_nullAmount_returnsEmpty() {
        assertThat(FundingValidations.milestone(null, project(new BigDecimal("200000")), BigDecimal.ZERO)).isEmpty();
    }

    // --- milestoneCoversAllocations(newAmount, totalAllocated) ---

    @Test
    void milestoneCoversAllocations_belowAllocated_isRejected() {
        assertThat(title(FundingValidations.milestoneCoversAllocations(new BigDecimal("50000"), new BigDecimal("60000"))))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_BELOW_ALLOCATED);
    }

    @Test
    void milestoneCoversAllocations_equalToAllocated_isAllowed() {
        assertThat(FundingValidations.milestoneCoversAllocations(new BigDecimal("60000"), new BigDecimal("60000"))).isEmpty();
    }

    @Test
    void milestoneCoversAllocations_aboveAllocated_isAllowed() {
        assertThat(FundingValidations.milestoneCoversAllocations(new BigDecimal("70000"), new BigDecimal("60000"))).isEmpty();
    }

    @Test
    void milestoneCoversAllocations_nullInputs_areAllowed() {
        assertThat(FundingValidations.milestoneCoversAllocations(null, new BigDecimal("60000"))).isEmpty();
        assertThat(FundingValidations.milestoneCoversAllocations(new BigDecimal("50000"), null)).isEmpty();
    }

    // --- allocation(allocatedAmount, milestone, eventType) ---

    @Test
    void allocation_required_forFunding_whenNull() {
        assertThat(title(FundingValidations.allocation(null, milestone(new BigDecimal("50000")), EventType.FUNDING)))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED);
    }

    @Test
    void allocation_required_forRefund_whenNull() {
        assertThat(title(FundingValidations.allocation(null, milestone(new BigDecimal("50000")), EventType.REFUND)))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED);
    }

    @Test
    void allocation_required_forSpending_whenNull() {
        assertThat(title(FundingValidations.allocation(null, milestone(new BigDecimal("50000")), EventType.SPENDING)))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED);
    }

    @Test
    void allocation_zero_isRejected() {
        assertThat(title(FundingValidations.allocation(BigDecimal.ZERO, milestone(new BigDecimal("50000")), EventType.FUNDING)))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_AMOUNT_INVALID);
    }

    @Test
    void allocation_exceedingMilestone_isRejected() {
        assertThat(title(FundingValidations.allocation(new BigDecimal("60000"), milestone(new BigDecimal("50000")), EventType.FUNDING)))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_EXCEEDS_MILESTONE);
    }

    @Test
    void allocation_equalToMilestone_isAllowed() {
        assertThat(FundingValidations.allocation(new BigDecimal("50000"), milestone(new BigDecimal("50000")), EventType.FUNDING)).isEmpty();
    }

    // --- eventTotal(eventType, total) ---

    @Test
    void eventTotal_fundingZero_isRejected() {
        assertThat(title(FundingValidations.eventTotal(EventType.FUNDING, BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_INVALID);
    }

    @Test
    void eventTotal_fundingNull_isRejected() {
        assertThat(title(FundingValidations.eventTotal(EventType.FUNDING, null)))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_INVALID);
    }

    @Test
    void eventTotal_refundZero_isRejected() {
        assertThat(title(FundingValidations.eventTotal(EventType.REFUND, BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_INVALID);
    }

    @Test
    void eventTotal_fundingPositive_isAllowed() {
        assertThat(FundingValidations.eventTotal(EventType.FUNDING, new BigDecimal("50000"))).isEmpty();
    }

    @Test
    void eventTotal_spendingZero_isRejected() {
        // every event's total is the sum of its allocations, so zero is invalid regardless of type
        assertThat(title(FundingValidations.eventTotal(EventType.SPENDING, BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_INVALID);
    }

    // --- spendDetail(eventType, category, vendor, amountFcy, spendCurrency, fxRate, amountRcy, spendDate, hash, notes) ---

    @Test
    void spendDetail_rejected_whenSpendFieldsOnNonSpendingEvent() {
        assertThat(title(FundingValidations.spendDetail(EventType.FUNDING,
                null, null, new BigDecimal("100000"), null, null, null, null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED);
    }

    @Test
    void spendDetail_allowed_whenNonSpendingEventHasNoSpendFields() {
        assertThat(FundingValidations.spendDetail(EventType.FUNDING,
                null, null, null, null, null, null, null, null, null)).isEmpty();
    }

    @Test
    void spendDetail_required_forSpendingEvent() {
        assertThat(title(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", null, "EUR", null, null, null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void spendDetail_rejected_whenFxRateMismatch() {
        // amountFcy (100000) != amountRcy (50000) * fxRate (3)
        assertThat(title(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), "EUR", new BigDecimal("3"),
                new BigDecimal("50000"), LocalDate.now(), null, null)))
                .isEqualTo(ErrorTitleConstants.FX_RATE_MISMATCH);
    }

    @Test
    void spendDetail_valid_forConsistentSpend() {
        // amountFcy (100000) == amountRcy (50000) * fxRate (2)
        assertThat(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), "EUR", new BigDecimal("2"),
                new BigDecimal("50000"), LocalDate.now(), null, null)).isEmpty();
    }

    @Test
    void spendFullyAllocated_rejected_whenAllocatedTotalExceedsAmountRcy() {
        assertThat(title(FundingValidations.spendFullyAllocated(
                EventType.SPENDING, new BigDecimal("60000"), new BigDecimal("50000"))))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_EXCEEDS_SPEND);
    }

    @Test
    void spendFullyAllocated_rejected_whenAllocatedTotalBelowAmountRcy() {
        assertThat(title(FundingValidations.spendFullyAllocated(
                EventType.SPENDING, new BigDecimal("40000"), new BigDecimal("50000"))))
                .isEqualTo(ErrorTitleConstants.SPEND_NOT_FULLY_ALLOCATED);
    }

    @Test
    void spendFullyAllocated_allowed_whenAllocatedTotalMatchesSpend() {
        assertThat(FundingValidations.spendFullyAllocated(
                EventType.SPENDING, new BigDecimal("50000"), new BigDecimal("50000.00"))).isEmpty();
    }

    @Test
    void spendFullyAllocated_ignoredForNonSpending() {
        assertThat(FundingValidations.spendFullyAllocated(
                EventType.FUNDING, new BigDecimal("999999"), null)).isEmpty();
    }

    // --- eventAmountWithinBudget(eventType, amountRcy, summedMilestoneBudget, summedProjectBudget) ---

    @Test
    void eventAmountWithinBudget_rejected_whenAmountExceedsMilestoneBudget() {
        assertThat(title(FundingValidations.eventAmountWithinBudget(
                EventType.SPENDING, new BigDecimal("60000"), new BigDecimal("50000"), new BigDecimal("200000"))))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_MILESTONES);
    }

    @Test
    void eventAmountWithinBudget_rejected_whenAmountExceedsProjectBudget() {
        // milestone budget unknown (null) -> only the project bound applies
        assertThat(title(FundingValidations.eventAmountWithinBudget(
                EventType.SPENDING, new BigDecimal("250000"), null, new BigDecimal("200000"))))
                .isEqualTo(ErrorTitleConstants.EVENT_AMOUNT_EXCEEDS_PROJECT);
    }

    @Test
    void eventAmountWithinBudget_allowed_whenWithinBothBudgets() {
        assertThat(FundingValidations.eventAmountWithinBudget(
                EventType.SPENDING, new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("200000"))).isEmpty();
    }

    @Test
    void eventAmountWithinBudget_ignoredForNonSpending() {
        assertThat(FundingValidations.eventAmountWithinBudget(
                EventType.FUNDING, new BigDecimal("999999"), new BigDecimal("1"), new BigDecimal("1"))).isEmpty();
    }

    // --- allocationTotal(sum, project) ---

    @Test
    void allocationTotal_exceedingProject_isRejected() {
        assertThat(title(FundingValidations.allocationTotal(new BigDecimal("250000"), project(new BigDecimal("200000")))))
                .isEqualTo(ErrorTitleConstants.ALLOCATION_TOTAL_EXCEEDS_PROJECT);
    }

    @Test
    void allocationTotal_withinProject_isAllowed() {
        assertThat(FundingValidations.allocationTotal(new BigDecimal("150000"), project(new BigDecimal("200000")))).isEmpty();
    }

    @Test
    void allocationTotal_skipped_whenProjectHasNoBudget() {
        assertThat(FundingValidations.allocationTotal(new BigDecimal("999999"), project(null))).isEmpty();
    }

    // --- milestones XOR sub-projects ---

    @Test
    void milestoneAllowed_rejected_whenProjectHasSubProjects() {
        assertThat(title(FundingValidations.milestoneAllowed(true)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS);
    }

    @Test
    void milestoneAllowed_allowed_whenNoSubProjects() {
        assertThat(FundingValidations.milestoneAllowed(false)).isEmpty();
    }

    @Test
    void subProjectAllowed_rejected_whenParentHasMilestones() {
        assertThat(title(FundingValidations.subProjectAllowed(true)))
                .isEqualTo(ErrorTitleConstants.SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES);
    }

    @Test
    void subProjectAllowed_allowed_whenNoMilestones() {
        assertThat(FundingValidations.subProjectAllowed(false)).isEmpty();
    }

    // --- projectAmount(total) ---

    @Test
    void projectAmount_zero_isRejected() {
        assertThat(title(FundingValidations.projectAmount(BigDecimal.ZERO))).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
    }

    @Test
    void projectAmount_negative_isRejected() {
        assertThat(title(FundingValidations.projectAmount(new BigDecimal("-5")))).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_INVALID);
    }

    @Test
    void projectAmount_positiveOrNull_isAllowed() {
        assertThat(FundingValidations.projectAmount(new BigDecimal("1"))).isEmpty();
        assertThat(FundingValidations.projectAmount(null)).isEmpty();
    }

    // --- subProjectAmount(childTotal, parent, otherSubProjectsTotal) ---

    @Test
    void subProjectAmount_valid_returnsEmpty() {
        assertThat(FundingValidations.subProjectAmount(new BigDecimal("200000"), project(new BigDecimal("500000")), BigDecimal.ZERO))
                .isEmpty();
    }

    @Test
    void subProjectAmount_childExceedsParent_isRejected() {
        assertThat(title(FundingValidations.subProjectAmount(new BigDecimal("600000"), project(new BigDecimal("500000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.SUBPROJECT_AMOUNT_EXCEEDS_PARENT);
    }

    @Test
    void subProjectAmount_cumulativeExceedsParent_isRejected() {
        // child (300000) fits, but together with existing sub-projects (300000) it exceeds the parent (500000)
        assertThat(title(FundingValidations.subProjectAmount(new BigDecimal("300000"), project(new BigDecimal("500000")), new BigDecimal("300000"))))
                .isEqualTo(ErrorTitleConstants.SUBPROJECT_TOTAL_EXCEEDS_PARENT);
    }

    @Test
    void subProjectAmount_skipped_whenParentHasNoBudget() {
        assertThat(FundingValidations.subProjectAmount(new BigDecimal("999999"), project(null), new BigDecimal("999999"))).isEmpty();
    }

    @Test
    void subProjectAmount_skipped_whenChildHasNoBudget() {
        assertThat(FundingValidations.subProjectAmount(null, project(new BigDecimal("500000")), BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void sumProjectTotals_excludesIdAndIgnoresNulls() {
        ProjectEntity p1 = ProjectEntity.builder().id("p1").totalAmount(new BigDecimal("100")).build();
        ProjectEntity p2 = ProjectEntity.builder().id("p2").totalAmount(new BigDecimal("200")).build();
        ProjectEntity p3 = ProjectEntity.builder().id("p3").totalAmount(null).build();

        assertThat(FundingValidations.sumProjectTotals(List.of(p1, p2, p3), "p1")).isEqualByComparingTo("200");
        assertThat(FundingValidations.sumProjectTotals(List.of(p1, p2, p3), null)).isEqualByComparingTo("300");
    }

    // --- sumMilestoneAmounts(milestones, excludeId) ---

    @Test
    void sumMilestoneAmounts_excludesIdAndIgnoresNulls() {
        MilestoneEntity m1 = MilestoneEntity.builder().id("m1").milestoneAmount(new BigDecimal("100")).build();
        MilestoneEntity m2 = MilestoneEntity.builder().id("m2").milestoneAmount(new BigDecimal("200")).build();
        MilestoneEntity m3 = MilestoneEntity.builder().id("m3").milestoneAmount(null).build();

        assertThat(FundingValidations.sumMilestoneAmounts(List.of(m1, m2, m3), "m1")).isEqualByComparingTo("200");
        assertThat(FundingValidations.sumMilestoneAmounts(List.of(m1, m2, m3), null)).isEqualByComparingTo("300");
    }
}
