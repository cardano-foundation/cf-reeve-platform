package org.cardanofoundation.lob.app.reporting.typeValidations.validator;

import static org.zalando.problem.Status.BAD_REQUEST;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTypeValidator;

@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceSheetValidator implements ReportTypeValidator {

    @Override
    public ReportTemplateType getSupportedReportTemplateType() {
        return ReportTemplateType.BALANCE_SHEET;
    }

    @Override
    public Either<Problem, Void> validateReportTemplateType(ReportTemplateEntity reportTemplateEntity) {
        if (areAllFieldsAccumulated(reportTemplateEntity.getFields())) {
            return Either.right(null);
        } else {
            log.debug("Balance sheet report template validation failed: not all fields are accumulated");
            return Either.left(Problem.builder()
                    .withTitle("ALL_FIELDS_MUST_BE_ACCUMULATED")
                    .withDetail("All fields in a Balance Sheet report template must be marked as accumulated.")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
    }

    private boolean areAllFieldsAccumulated(List<ReportTemplateFieldEntity> fields) {
        if (fields.isEmpty()) {
            return true;
        }
        Optional<ReportTemplateFieldEntity> notAccumulated = fields.stream().filter(f -> !f.getDateRange().name().startsWith("ACCUMULATED")).findAny();
        if (notAccumulated.isPresent()) {
            return false;
        }
        return fields.stream().map(f -> areAllFieldsAccumulated(f.getChildFields())).reduce(true, (a, b) -> a && b);
    }

}
