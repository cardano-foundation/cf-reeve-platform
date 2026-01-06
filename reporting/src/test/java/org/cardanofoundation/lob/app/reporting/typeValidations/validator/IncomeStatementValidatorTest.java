package org.cardanofoundation.lob.app.reporting.typeValidations.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class IncomeStatementValidatorTest {

    @InjectMocks
    private IncomeStatementValidator validator;

    @Test
    void returnsIncomeStatementAsSupportedType() {
        assertEquals(ReportTemplateType.INCOME_STATEMENT, validator.getSupportedReportTemplateType());
    }

    @Test
    void validateReportTemplateType_shouldReturnProblem() {
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);

        ReportTemplateFieldEntity field1 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity field2 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity field3 = mock(ReportTemplateFieldEntity.class);

        when(field2.getChildFields()).thenReturn(List.of(field3));
        when(field1.getChildFields()).thenReturn(List.of(field2));
        when(reportTemplateEntity.getFields()).thenReturn(List.of(field1));

        when(field1.isAccumulatedYearly()).thenReturn(Boolean.TRUE);
        when(field2.isAccumulatedYearly()).thenReturn(Boolean.TRUE);
        when(field3.isAccumulatedYearly()).thenReturn(Boolean.FALSE);

        Either<Problem, Void> response = validator.validateReportTemplateType(reportTemplateEntity);

        assertEquals(true, response.isLeft());
        assertEquals("ALL_FIELDS_MUST_BE_ACCUMULATED_YEARLY", response.getLeft().getTitle());
    }

    @Test
    void validateReportTemplateType_shouldReturnTrue() {
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);

        ReportTemplateFieldEntity field1 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity field2 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity field3 = mock(ReportTemplateFieldEntity.class);

        when(field2.getChildFields()).thenReturn(List.of(field3));
        when(field1.getChildFields()).thenReturn(List.of(field2));
        when(reportTemplateEntity.getFields()).thenReturn(List.of(field1));

        when(field1.isAccumulatedYearly()).thenReturn(Boolean.TRUE);
        when(field2.isAccumulatedYearly()).thenReturn(Boolean.TRUE);
        when(field3.isAccumulatedYearly()).thenReturn(Boolean.TRUE);

        Either<Problem, Void> response = validator.validateReportTemplateType(reportTemplateEntity);

        assertEquals(true, response.isRight());
    }
}
