package org.cardanofoundation.lob.app.reporting.typeValidations.validator;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTypeValidator;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncomeStatementValidator implements ReportTypeValidator {
    @Override
    public ReportTemplateType getSupportedReportTemplateType() {
        return ReportTemplateType.INCOME_STATEMENT;
    }

    @Override
    public Either<ProblemDetail, Void> validateReportTemplateType(ReportTemplateEntity reportTemplateEntity) {
        if (areAllFieldsAccumulated(reportTemplateEntity.getFields())) {
            return Either.right(null);
        } else {
            log.debug("Balance sheet report template validation failed: not all fields are accumulated");
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(BAD_REQUEST, "All fields in an Income statement report template must be marked as accumulated yearly.");
            problemDetail.setTitle("ALL_FIELDS_MUST_BE_ACCUMULATED_YEARLY");
            return Either.left(problemDetail);
        }
    }

    private boolean areAllFieldsAccumulated(List<ReportTemplateFieldEntity> fields) {
        if (fields.isEmpty()) {
            return true;
        }
        Optional<ReportTemplateFieldEntity> notAccumulatedYearly = fields.stream().filter(f -> !f.getDateRange().equals(ReportFieldDateRange.ACCUMULATED_YEAR_TO_PERIOD_END) && f.getChildFields().isEmpty()).findAny();
        if (notAccumulatedYearly.isPresent()) {
            return false;
        }
        return fields.stream().map(f -> areAllFieldsAccumulated(f.getChildFields())).reduce(true, (a, b) -> a && b);
    }
}
