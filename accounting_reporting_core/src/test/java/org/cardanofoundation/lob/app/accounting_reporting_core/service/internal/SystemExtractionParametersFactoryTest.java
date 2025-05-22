package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@ExtendWith(MockitoExtension.class)
class SystemExtractionParametersFactoryTest {

    @Mock
    private OrganisationPublicApiIF organisationPublicApi;
    @Mock
    private AccountingPeriodCalculator accountingPeriodCalculator;

    @InjectMocks
    private SystemExtractionParametersFactory systemExtractionParametersFactory;

    @Test
    void createSystemExtractionParameters_OrgnotFound() {

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.empty());

        Either<Problem, SystemExtractionParameters> systemExtractionParameters = systemExtractionParametersFactory.createSystemExtractionParameters("org123");

        Assertions.assertTrue(systemExtractionParameters.isLeft());
    }

    @Test
    void createSystemExtractionParameters_OrgFound() {
        Organisation org = mock(Organisation.class);
        LocalDate now = LocalDate.now();

        when(organisationPublicApi.findByOrganisationId("org123")).thenReturn(Optional.of(org));
        when(accountingPeriodCalculator.calculateAccountingPeriod(org)).thenReturn(Range.of(LocalDate.EPOCH, now));

        Either<Problem, SystemExtractionParameters> systemExtractionParameters = systemExtractionParametersFactory.createSystemExtractionParameters("org123");

        Assertions.assertTrue(systemExtractionParameters.isRight());
        Assertions.assertEquals("org123", systemExtractionParameters.get().getOrganisationId());
        Assertions.assertEquals(LocalDate.EPOCH, systemExtractionParameters.get().getAccountPeriodFrom());
        Assertions.assertEquals(now, systemExtractionParameters.get().getAccountPeriodTo());
    }
}
