package org.cardanofoundation.lob.app.reporting.typeValidations;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

public interface ReportTypeValidator {

    ReportTemplateType getSupportedReportTemplateType();
    Either<Problem, Void> validateReportTemplateType(ReportTemplateEntity reportTemplateEntity);

}
