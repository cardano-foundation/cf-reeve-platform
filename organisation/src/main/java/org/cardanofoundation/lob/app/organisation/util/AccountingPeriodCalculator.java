package org.cardanofoundation.lob.app.organisation.util;

import java.time.Clock;
import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import lombok.val;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.Range;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Service
@RequiredArgsConstructor
public class AccountingPeriodCalculator {

    private final Clock clock;

    public Range<LocalDate> calculateAccountingPeriod(Organisation organisation) {
        val toDate = LocalDate.now(clock).minusDays(1); // we exclude today
        val fromDate = toDate.minusDays(organisation.getAccountPeriodDays());

        return Range.of(fromDate, toDate);
    }

}
