package org.cardanofoundation.lob.app.reporting.typeValidations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class ReportTemplateTypeValidatorTest {

    @Test
    void validateReportTemplateType_NoValidatorFound_ReturnsRight() {
        ReportTemplateTypeValidator validator = new ReportTemplateTypeValidator(List.of());

        ReportTemplateEntity entity = mock(ReportTemplateEntity.class);
        when(entity.getReportTemplateType()).thenReturn(ReportTemplateType.BALANCE_SHEET);

        Either<ProblemDetail, Void> response = validator.validateReportTemplateType(entity);
        assertTrue(response.isRight());
    }

    @Test
    void validateReportTemplateType_ValidatorFound_CallsValidator() {
        ReportTypeValidator typeValidator = mock(ReportTypeValidator.class);
        when(typeValidator.getSupportedReportTemplateType()).thenReturn(ReportTemplateType.INCOME_STATEMENT);
        when(typeValidator.validateReportTemplateType(org.mockito.ArgumentMatchers.any())).thenReturn(Either.right(null));

        ReportTemplateTypeValidator validator = new ReportTemplateTypeValidator(List.of(typeValidator));

        ReportTemplateEntity entity = mock(ReportTemplateEntity.class);
        when(entity.getReportTemplateType()).thenReturn(ReportTemplateType.INCOME_STATEMENT);

        Either<ProblemDetail, Void> response = validator.validateReportTemplateType(entity);
        assertTrue(response.isRight());
    }
}
