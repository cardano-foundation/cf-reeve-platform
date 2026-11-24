package org.cardanofoundation.lob.app.reporting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportStandardEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportStandardRepository;
import org.cardanofoundation.lob.app.reporting.util.Constants;

/**
 * Checks a report template's report standard (shown to users as "Standard") against what the template builder offers: the labels of
 * the organisation's active standards plus "Not applicable". Shared by the API and the CSV import so both
 * accept exactly the same values with the same message.
 */
@Component
@RequiredArgsConstructor
public class ReportStandardResolver {

    // Always offered by the template builder next to the organisation's standards, without being a stored standard
    static final String NOT_APPLICABLE = "Not applicable";

    private final ReportStandardRepository reportStandardRepository;

    /**
     * Returns the matching option's exact label (matching ignores case and surrounding spaces), which is
     * what the template stores, or a problem naming the valid options.
     */
    public Either<ProblemDetail, String> resolve(String organisationId, String reportStandard) {
        if (reportStandard == null || reportStandard.isBlank()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Standard is required");
            problem.setTitle(Constants.INVALID_REPORT_STANDARD);
            return Either.left(problem);
        }
        List<String> options = options(organisationId);
        Optional<String> match = options.stream()
                .filter(option -> option.equalsIgnoreCase(reportStandard.trim()))
                .findFirst();
        if (match.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Standard not found: " + reportStandard + ". Options are: " + String.join(", ", options));
            problem.setTitle(Constants.INVALID_REPORT_STANDARD);
            return Either.left(problem);
        }
        return Either.right(match.get());
    }

    private List<String> options(String organisationId) {
        List<String> options = new ArrayList<>(reportStandardRepository.findAllByOrganisationId(organisationId).stream()
                .filter(ReportStandardEntity::isActive)
                .map(ReportStandardEntity::getLabel)
                .sorted()
                .toList());
        if (options.stream().noneMatch(NOT_APPLICABLE::equalsIgnoreCase)) {
            options.add(NOT_APPLICABLE);
        }
        return options;
    }
}
