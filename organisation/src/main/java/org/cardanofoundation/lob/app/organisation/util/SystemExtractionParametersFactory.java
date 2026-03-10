package org.cardanofoundation.lob.app.organisation.util;

import java.time.LocalDate;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;
import org.apache.commons.lang3.Range;

import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

@Slf4j
@RequiredArgsConstructor
@Service
public class SystemExtractionParametersFactory {

    private final OrganisationPublicApiIF organisationPublicApi;
    private final AccountingPeriodCalculator accountingPeriodCalculator;

    public Either<ProblemDetail, org.cardanofoundation.lob.app.organisation.domain.SystemExtractionParameters> createSystemExtractionParameters(String organisationId) {
        Optional<Organisation> organisationM = organisationPublicApi.findByOrganisationId(organisationId);

        if (organisationM.isEmpty()) {
            log.error("Organisation not found for id: {}", organisationId);


            ProblemDetail issue = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Organisation not found for id: %s".formatted(organisationId));
                    issue.setTitle("ORGANISATION_NOT_FOUND");

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
