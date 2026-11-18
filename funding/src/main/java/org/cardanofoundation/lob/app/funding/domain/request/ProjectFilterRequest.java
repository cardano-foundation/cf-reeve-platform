package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.cardanofoundation.lob.app.funding.domain.enums.ProjectAmountTarget;

/**
 * Optional, AND-combined filters for the organisation projects list. They apply to root projects; a
 * {@code null} (or empty) field disables that filter, and with every field unset the list is unfiltered.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProjectFilterRequest {

    /** Case-insensitive partial match on the root's title or any sub-project or milestone title beneath it, at any level. */
    private String search;

    /** Matches the root's base currency, case-insensitively. */
    private List<String> currencies;

    /** Which amount {@link #minAmount}/{@link #maxAmount} apply to; {@code null} means {@link ProjectAmountTarget#BUDGETED}. */
    private ProjectAmountTarget amountTarget;

    /** Inclusive lower bound on the target amount. */
    private BigDecimal minAmount;

    /** Inclusive upper bound on the target amount. */
    private BigDecimal maxAmount;

}
