package org.cardanofoundation.lob.app.reporting.typeValidations;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTemplateTypeValidator {

    private final List<ReportTypeValidator> validators;

    public Either<Problem, Void> validateReportTemplateType(ReportTemplateEntity reportTemplateEntity) {
        Optional<ReportTypeValidator> validator = validators.stream().filter(v -> v.getSupportedReportTemplateType().equals(reportTemplateEntity.getReportTemplateType())).findFirst();
        if(validator.isPresent()) {
            return validator.get().validateReportTemplateType(reportTemplateEntity);
        } else {
            log.debug("No validator found for report template type: {}", reportTemplateEntity.getReportTemplateType());
            return Either.right(null);
        }
    }

}
