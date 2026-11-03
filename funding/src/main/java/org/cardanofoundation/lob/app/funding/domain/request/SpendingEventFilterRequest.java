package org.cardanofoundation.lob.app.funding.domain.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

/**
 * Optional, AND-combined filters for the organisation events list. A {@code null} (or empty) field
 * disables that filter; with every field unset the list is unfiltered.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SpendingEventFilterRequest {

    /** Case-insensitive partial match on the title of any project the event is allocated to. */
    private String projectTitle;

    private List<EventStatus> statuses;

    private List<EventType> eventTypes;

    /** Matches the event's reporting currency or its foreign (spend) currency, case-insensitively. */
    private String currency;

    /** Inclusive lower bound on the event date. */
    private LocalDate dateFrom;

    /** Inclusive upper bound on the event date. */
    private LocalDate dateTo;

    /** Inclusive lower bound on the event's total amount. */
    private BigDecimal minAmount;

    /** Inclusive upper bound on the event's total amount. */
    private BigDecimal maxAmount;

}
