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

    private String detail(Optional<ProblemDetail> p) {
        return p.orElseThrow().getDetail();
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
    void allocation_exceedingMilestone_isAllowed() {
        // The hard cap against the milestone's budget was removed — an allocation may now push
        // cumulative spend past it; that overspend is surfaced (see isOverspend), not rejected.
        assertThat(FundingValidations.allocation(new BigDecimal("60000"), milestone(new BigDecimal("50000")), EventType.FUNDING)).isEmpty();
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

    // --- eventCurrencyMatchesMilestone(eventCurrencyRcy, milestone) ---

    @Test
    void eventCurrencyMatchesMilestone_matching_isAllowed() {
        assertThat(FundingValidations.eventCurrencyMatchesMilestone("USD", milestone(new BigDecimal("50000"))))
                .isEmpty();
    }

    @Test
    void eventCurrencyMatchesMilestone_mismatched_isRejected() {
        assertThat(title(FundingValidations.eventCurrencyMatchesMilestone("EUR", milestone(new BigDecimal("50000")))))
                .isEqualTo(ErrorTitleConstants.EVENT_CURRENCY_MISMATCH);
    }

    @Test
    void eventCurrencyMatchesMilestone_caseSensitive_isRejected() {
        assertThat(title(FundingValidations.eventCurrencyMatchesMilestone("usd", milestone(new BigDecimal("50000")))))
                .isEqualTo(ErrorTitleConstants.EVENT_CURRENCY_MISMATCH);
    }

    @Test
    void eventCurrencyMatchesMilestone_nullEventCurrency_isAllowed() {
        assertThat(FundingValidations.eventCurrencyMatchesMilestone(null, milestone(new BigDecimal("50000"))))
                .isEmpty();
    }

    @Test
    void eventCurrencyMatchesMilestone_nullMilestoneCurrency_isAllowed() {
        MilestoneEntity milestoneWithoutCurrency = MilestoneEntity.builder().id("m1").milestoneAmount(new BigDecimal("50000")).build();
        assertThat(FundingValidations.eventCurrencyMatchesMilestone("USD", milestoneWithoutCurrency))
                .isEmpty();
    }

    // --- spendDetail(eventType, category, vendor, amountFcy, currencyFcy, fxRate, amountRcy, currencyRcy, hash, notes) ---

    @Test
    void spendDetail_rejected_whenSpendFieldsOnNonSpendingEvent() {
        assertThat(title(FundingValidations.spendDetail(EventType.FUNDING,
                null, null, new BigDecimal("100000"), null, null, null, null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_NOT_ALLOWED);
    }

    @Test
    void spendDetail_allowed_whenNonSpendingEventHasAmountRcyButNoSpendOnlyFields() {
        assertThat(FundingValidations.spendDetail(EventType.FUNDING,
                null, null, null, null, null, new BigDecimal("100000"), null, null, null)).isEmpty();
    }

    @Test
    void spendDetail_required_whenAmountRcyMissingOnNonSpendingEvent() {
        assertThat(title(FundingValidations.spendDetail(EventType.FUNDING,
                null, null, null, null, null, null, null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void spendDetail_allowed_whenRefundEventHasAmountRcyButNoSpendOnlyFields() {
        assertThat(FundingValidations.spendDetail(EventType.REFUND,
                null, null, null, null, null, new BigDecimal("50000"), null, null, null)).isEmpty();
    }

    @Test
    void spendDetail_required_forSpendingEvent() {
        assertThat(title(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", null, "EUR", null, null, null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void spendDetail_required_whenCurrencyRcyMissing() {
        // amountFcy/amountRcy/fxRate/currencyFcy present, currencyRcy (the last positional arg before hash) absent
        assertThat(title(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), "EUR", new BigDecimal("2"),
                new BigDecimal("50000"), null, null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void spendDetail_required_whenCurrencyFcyMissing() {
        assertThat(title(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), null, new BigDecimal("2"),
                new BigDecimal("50000"), "USD", null, null)))
                .isEqualTo(ErrorTitleConstants.SPEND_FIELDS_REQUIRED);
    }

    @Test
    void spendDetail_message_namesOnlyTheActuallyMissingField() {
        // Only currencyFcy is absent — the message must not claim the other four are missing too.
        assertThat(detail(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), null, new BigDecimal("2"),
                new BigDecimal("50000"), "USD", null, null)))
                .isEqualTo("Missing required field(s) for a SPENDING event: currencyFcy");
    }

    @Test
    void spendDetail_message_listsEveryMissingField_whenSeveralAreAbsent() {
        assertThat(detail(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", null, null, null, null, "USD", null, null)))
                .isEqualTo("Missing required field(s) for a SPENDING event: amountFcy, amountRcy, currencyFcy, fxRate");
    }

    @Test
    void spendDetail_allowed_whenFxRateInconsistentWithAmounts() {
        // The fxRate/amountFcy/amountRcy consistency check was intentionally removed: the method now
        // only requires the fields to be present, not internally consistent (100000 != 50000 * 2.5).
        assertThat(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), "EUR", new BigDecimal("2.5"),
                new BigDecimal("50000"), "USD", null, null)).isEmpty();
    }

    @Test
    void spendDetail_valid_forConsistentSpend() {
        // All required fields are present - validation passes
        assertThat(FundingValidations.spendDetail(EventType.SPENDING,
                "Personnel", "Vendor", new BigDecimal("100000"), "EUR", new BigDecimal("2"),
                new BigDecimal("50000"), "USD", null, null)).isEmpty();
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
    void spendFullyAllocated_ignoredWhenAmountRcyAbsent() {
        assertThat(FundingValidations.spendFullyAllocated(
                EventType.FUNDING, new BigDecimal("999999"), null)).isEmpty();
    }

    @Test
    void spendFullyAllocated_appliesToFunding_rejectedWhenAllocatedTotalBelowAmountRcy() {
        assertThat(title(FundingValidations.spendFullyAllocated(
                EventType.FUNDING, new BigDecimal("40000"), new BigDecimal("50000"))))
                .isEqualTo(ErrorTitleConstants.SPEND_NOT_FULLY_ALLOCATED);
    }

    @Test
    void spendFullyAllocated_appliesToRefund_allowedWhenAllocatedTotalMatches() {
        assertThat(FundingValidations.spendFullyAllocated(
                EventType.REFUND, new BigDecimal("50000"), new BigDecimal("50000.00"))).isEmpty();
    }

    // --- isOverspend(cumulativeSpend, budget) ---

    @Test
    void isOverspend_true_whenCumulativeExceedsBudget() {
        assertThat(FundingValidations.isOverspend(new BigDecimal("60000"), new BigDecimal("50000"))).isTrue();
    }

    @Test
    void isOverspend_false_whenCumulativeEqualsBudget() {
        assertThat(FundingValidations.isOverspend(new BigDecimal("50000"), new BigDecimal("50000"))).isFalse();
    }

    @Test
    void isOverspend_false_whenCumulativeWithinBudget() {
        assertThat(FundingValidations.isOverspend(new BigDecimal("40000"), new BigDecimal("50000"))).isFalse();
    }

    @Test
    void isOverspend_false_whenBudgetUnknown() {
        assertThat(FundingValidations.isOverspend(new BigDecimal("999999"), null)).isFalse();
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

    // --- firstDuplicate(values) ---

    @Test
    void firstDuplicate_findsFirstRepeatAndIgnoresNulls() {
        assertThat(FundingValidations.firstDuplicate(List.of("A", "B", "A"))).contains("A");
        assertThat(FundingValidations.firstDuplicate(List.of("A", "B", "C"))).isEmpty();
        // Nulls are ignored — two nulls are not a duplicate.
        assertThat(FundingValidations.firstDuplicate(java.util.Arrays.asList("A", null, null))).isEmpty();
    }

    // --- eventDateNotInFuture(eventDate) ---

    @Test
    void eventDateNotInFuture_pastDate_isAllowed() {
        assertThat(FundingValidations.eventDateNotInFuture(LocalDate.now().minusDays(1))).isEmpty();
    }

    @Test
    void eventDateNotInFuture_today_isAllowed() {
        assertThat(FundingValidations.eventDateNotInFuture(LocalDate.now())).isEmpty();
    }

    @Test
    void eventDateNotInFuture_nullDate_isAllowed() {
        assertThat(FundingValidations.eventDateNotInFuture(null)).isEmpty();
    }

    @Test
    void eventDateNotInFuture_futureDate_isRejected() {
        assertThat(title(FundingValidations.eventDateNotInFuture(LocalDate.now().plusDays(1))))
                .isEqualTo(ErrorTitleConstants.EVENT_DATE_IN_FUTURE);
    }

    // --- eventDateRequired(eventDate) ---

    @Test
    void eventDateRequired_present_isAllowed() {
        assertThat(FundingValidations.eventDateRequired(LocalDate.now())).isEmpty();
    }

    @Test
    void eventDateRequired_null_isRejected() {
        assertThat(title(FundingValidations.eventDateRequired(null)))
                .isEqualTo(ErrorTitleConstants.EVENT_DATE_REQUIRED);
    }

    // --- spendAmountsPositive(eventType, amountFcy, fxRate) ---

    @Test
    void spendAmountsPositive_positiveValues_isAllowed() {
        assertThat(FundingValidations.spendAmountsPositive(EventType.SPENDING, new BigDecimal("100"), new BigDecimal("1.1")))
                .isEmpty();
    }

    @Test
    void spendAmountsPositive_zeroAmountFcy_isRejected() {
        assertThat(title(FundingValidations.spendAmountsPositive(EventType.SPENDING, BigDecimal.ZERO, new BigDecimal("1.1"))))
                .isEqualTo(ErrorTitleConstants.AMOUNT_FCY_INVALID);
    }

    @Test
    void spendAmountsPositive_negativeAmountFcy_isRejected() {
        assertThat(title(FundingValidations.spendAmountsPositive(EventType.SPENDING, new BigDecimal("-1"), new BigDecimal("1.1"))))
                .isEqualTo(ErrorTitleConstants.AMOUNT_FCY_INVALID);
    }

    @Test
    void spendAmountsPositive_zeroFxRate_isRejected() {
        assertThat(title(FundingValidations.spendAmountsPositive(EventType.SPENDING, new BigDecimal("100"), BigDecimal.ZERO)))
                .isEqualTo(ErrorTitleConstants.FX_RATE_INVALID);
    }

    @Test
    void spendAmountsPositive_negativeFxRate_isRejected() {
        assertThat(title(FundingValidations.spendAmountsPositive(EventType.SPENDING, new BigDecimal("100"), new BigDecimal("-1.1"))))
                .isEqualTo(ErrorTitleConstants.FX_RATE_INVALID);
    }

    @Test
    void spendAmountsPositive_nullValues_areAllowed() {
        // Presence is enforced separately by spendDetail(); this only guards against a supplied
        // zero/negative value.
        assertThat(FundingValidations.spendAmountsPositive(EventType.SPENDING, null, null)).isEmpty();
    }

    @Test
    void spendAmountsPositive_ignoredForNonSpendingEvents() {
        assertThat(FundingValidations.spendAmountsPositive(EventType.FUNDING, BigDecimal.ZERO, BigDecimal.ZERO)).isEmpty();
        assertThat(FundingValidations.spendAmountsPositive(EventType.REFUND, new BigDecimal("-1"), new BigDecimal("-1"))).isEmpty();
    }

    // --- currencyCode(currency, registeredAndActive) ---

    @Test
    void currencyCode_registeredAndActive_isAllowed() {
        // The org's currency table is the source of truth — it also covers non-ISO-4217 codes such
        // as ADA (registered under ISO 24165), not just fiat ISO 4217 codes.
        assertThat(FundingValidations.currencyCode("USD", true)).isEmpty();
        assertThat(FundingValidations.currencyCode("ADA", true)).isEmpty();
    }

    @Test
    void currencyCode_notRegisteredOrInactive_isRejected() {
        assertThat(title(FundingValidations.currencyCode("ABC", false)))
                .isEqualTo(ErrorTitleConstants.CURRENCY_INVALID);
    }

    @Test
    void currencyCode_nullOrBlank_isAllowed() {
        // Presence is enforced separately by callers that require a currency; the flag is irrelevant
        // when there's no currency to check.
        assertThat(FundingValidations.currencyCode(null, false)).isEmpty();
        assertThat(FundingValidations.currencyCode("", false)).isEmpty();
        assertThat(FundingValidations.currencyCode("  ", false)).isEmpty();
    }

    @Test
    void currencyCode_qualifiedIsoForm_isAllowed() {
        // Already-qualified ISO_xxx:... codes (see SpendingEventService#toCurrency) are left unchecked
        // regardless of the flag.
        assertThat(FundingValidations.currencyCode("ISO_24165:ADA", false)).isEmpty();
    }
}
