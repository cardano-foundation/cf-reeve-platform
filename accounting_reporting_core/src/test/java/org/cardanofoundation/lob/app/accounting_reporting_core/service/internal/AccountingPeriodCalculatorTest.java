package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.apache.commons.lang3.Range;
import org.mockito.Mockito;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.util.AccountingPeriodCalculator;

class AccountingPeriodCalculatorTest {

    private Clock clock;
    private AccountingPeriodCalculator accountingPeriodCalculator;

    @BeforeEach
    void setUp() {
        clock = Mockito.mock(Clock.class);
        accountingPeriodCalculator = new AccountingPeriodCalculator(clock);
    }

    @Test
    void shouldCalculateCorrectAccountingPeriod() {
        // Given
        LocalDate currentDate = LocalDate.of(2024, 9, 13);
        Organisation organisation = Mockito.mock(Organisation.class);
        Mockito.when(organisation.getAccountPeriodDays()).thenReturn(30);

        // Mock the clock to return a specific date
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2024-09-13T00:00:00Z"));
        Mockito.when(clock.getZone()).thenReturn(ZoneId.systemDefault());

        // When
        Range<LocalDate> result = accountingPeriodCalculator.calculateAccountingPeriod(organisation);

        // Then
        LocalDate expectedToDate = LocalDate.of(2024, 9, 12); // One day before the current date
        LocalDate expectedFromDate = expectedToDate.minusDays(30);

        assertThat(result.getMinimum()).isEqualTo(expectedFromDate);
        assertThat(result.getMaximum()).isEqualTo(expectedToDate);
    }

}
