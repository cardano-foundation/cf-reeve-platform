package org.cardanofoundation.lob.app.funding.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

class FundingValidationsTest {

    private static final LocalDate FUTURE = LocalDate.now().plusYears(1);
    private static final LocalDate PAST = LocalDate.now().minusDays(1);

    private ProjectEntity project(BigDecimal total) {
        return ProjectEntity.builder().id("p1").organisationId("org1").totalAmount(total).currency("USD").build();
    }

    private MilestoneEntity milestone(BigDecimal amount) {
        return MilestoneEntity.builder().id("m1").milestoneAmount(amount).currency("USD").build();
    }

    private String title(Optional<ProblemDetail> p) {
        return p.orElseThrow().getTitle();
    }

    // --- milestone(amount, date, project, otherTotal) ---

    @Test
    void milestone_valid_returnsEmpty() {
        assertThat(FundingValidations.milestone(new BigDecimal("50000"), FUTURE, project(new BigDecimal("200000")), BigDecimal.ZERO))
                .isEmpty();
    }

    @Test
    void milestone_today_isAllowed() {
        assertThat(FundingValidations.milestone(new BigDecimal("50000"), LocalDate.now(), project(new BigDecimal("200000")), BigDecimal.ZERO))
                .isEmpty();
    }

    @Test
    void milestone_dateInPast_isRejected() {
        Optional<ProblemDetail> result = FundingValidations.milestone(new BigDecimal("50000"), PAST, project(new BigDecimal("200000")), BigDecimal.ZERO);

        assertThat(title(result)).isEqualTo(ErrorTitleConstants.MILESTONE_DATE_IN_PAST);
        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    void milestone_zeroAmount_isRejected() {
        assertThat(title(FundingValidations.milestone(BigDecimal.ZERO, FUTURE, project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_INVALID);
    }

    @Test
    void milestone_negativeAmount_isRejected() {
        assertThat(title(FundingValidations.milestone(new BigDecimal("-1"), FUTURE, project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_INVALID);
    }

    @Test
    void milestone_amountExceedsProject_isRejected() {
        assertThat(title(FundingValidations.milestone(new BigDecimal("250000"), FUTURE, project(new BigDecimal("200000")), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_EXCEEDS_PROJECT);
    }

    @Test
    void milestone_cumulativeExceedsProject_isRejected() {
        // each milestone (50000) is within the project total, but the running sum exceeds it
        assertThat(title(FundingValidations.milestone(new BigDecimal("50000"), FUTURE, project(new BigDecimal("200000")), new BigDecimal("180000"))))
                .isEqualTo(ErrorTitleConstants.MILESTONE_TOTAL_EXCEEDS_PROJECT);
    }

    @Test
    void milestone_amountChecksSkipped_whenProjectHasNoBudget() {
        // sub-projects carry a null totalAmount → amount/cumulative checks don't apply, date still does
        assertThat(FundingValidations.milestone(new BigDecimal("999999"), FUTURE, project(null), new BigDecimal("999999")))
                .isEmpty();
    }

    @Test
    void milestone_nullAmountAndDate_returnsEmpty() {
        assertThat(FundingValidations.milestone(null, null, project(new BigDecimal("200000")), BigDecimal.ZERO)).isEmpty();
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
    void allocation_optional_forSpending_whenNull() {
        assertThat(FundingValidations.allocation(null, milestone(new BigDecimal("50000")), EventType.SPENDING)).isEmpty();
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
    void eventTotal_spendingZero_isAllowed() {
        // SPENDING totals derive from line items and are not constrained here
        assertThat(FundingValidations.eventTotal(EventType.SPENDING, BigDecimal.ZERO)).isEmpty();
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
