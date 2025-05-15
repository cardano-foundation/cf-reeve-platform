package org.cardanofoundation.lob.app.accounting_reporting_core.service.internal;

import java.time.LocalDate;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.SystemExtractionParameters;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.assistance.AccountingPeriodCalculator;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Slf4j
@RequiredArgsConstructor
public class SystemExtractionParametersFactory {

    private final OrganisationPublicApiIF organisationPublicApi;
    private final AccountingPeriodCalculator accountingPeriodCalculator;

    public Either<Problem, SystemExtractionParameters> createSystemExtractionParameters(String organisationId) {
        Optional<Organisation> organisationM = organisationPublicApi.findByOrganisationId(organisationId);

        if (organisationM.isEmpty()) {
            log.error("Organisation not found for id: {}", organisationId);

            ThrowableProblem issue = Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Organisation not found for id: \{organisationId}")
                    .build();

            return Either.left(issue);
        }
        Organisation org = organisationM.orElseThrow();

        Range<LocalDate> period = accountingPeriodCalculator.calculateAccountingPeriod(org);

        return Either.right(SystemExtractionParameters.builder()
                .organisationId(organisationId)
                .accountPeriodFrom(period.getMinimum())
                .accountPeriodTo(period.getMaximum())
                .build()
        );
    }

}
