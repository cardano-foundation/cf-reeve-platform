package org.cardanofoundation.lob.app.reporting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.AccountingRegime;
import org.cardanofoundation.lob.app.organisation.repository.AccountingRegimeRepository;
import org.cardanofoundation.lob.app.reporting.util.Constants;

/**
 * Checks a report template's accounting regime against what the template builder offers: the labels of
 * the organisation's active regimes plus "Not applicable". Shared by the API and the CSV import so both
 * accept exactly the same values with the same message.
 */
@Component
@RequiredArgsConstructor
public class AccountingRegimeResolver {

    // Always offered by the template builder next to the organisation's regimes, without being a stored regime
    static final String NOT_APPLICABLE = "Not applicable";

    private final AccountingRegimeRepository accountingRegimeRepository;

    /**
     * Returns the matching option's exact label (matching ignores case and surrounding spaces), which is
     * what the template stores, or a problem naming the valid options.
     */
    public Either<ProblemDetail, String> resolve(String organisationId, String accountingRegime) {
        if (accountingRegime == null || accountingRegime.isBlank()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Accounting regime is required");
            problem.setTitle(Constants.INVALID_ACCOUNTING_REGIME);
            return Either.left(problem);
        }
        List<String> options = options(organisationId);
        Optional<String> match = options.stream()
                .filter(option -> option.equalsIgnoreCase(accountingRegime.trim()))
                .findFirst();
        if (match.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Accounting regime not found: " + accountingRegime + ". Options are: " + String.join(", ", options));
            problem.setTitle(Constants.INVALID_ACCOUNTING_REGIME);
            return Either.left(problem);
        }
        return Either.right(match.get());
    }

    private List<String> options(String organisationId) {
        List<String> options = new ArrayList<>(accountingRegimeRepository.findAllByOrganisationId(organisationId).stream()
                .filter(AccountingRegime::isActive)
                .map(AccountingRegime::getLabel)
                .sorted()
                .toList());
        if (options.stream().noneMatch(NOT_APPLICABLE::equalsIgnoreCase)) {
            options.add(NOT_APPLICABLE);
        }
        return options;
    }
}
