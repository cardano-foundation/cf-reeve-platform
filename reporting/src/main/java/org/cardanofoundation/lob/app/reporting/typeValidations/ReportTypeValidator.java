package org.cardanofoundation.lob.app.reporting.typeValidations;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

public interface ReportTypeValidator {

    ReportTemplateType getSupportedReportTemplateType();
    Either<ProblemDetail, Void> validateReportTemplateType(ReportTemplateEntity reportTemplateEntity);

}
