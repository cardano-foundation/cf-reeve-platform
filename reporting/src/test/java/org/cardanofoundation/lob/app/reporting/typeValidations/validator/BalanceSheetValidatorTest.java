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
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class BalanceSheetValidatorTest {

    @InjectMocks
    private BalanceSheetValidator validator;

    @Test
    void returnsBalanceSheetAsSupportedType() {
        assertEquals(ReportTemplateType.BALANCE_SHEET, validator.getSupportedReportTemplateType());
    }

    @Test
    void validateReportTemplateType_shouldReturnProblem() {
        ReportTemplateEntity reportTemplateEntity = mock(ReportTemplateEntity.class);

        ReportTemplateFieldEntity field1 = mock(ReportTemplateFieldEntity.class);

        when(reportTemplateEntity.getFields()).thenReturn(List.of(field1));

        when(field1.getDateRange()).thenReturn(ReportFieldDateRange.PERIOD);
        Either<Problem, Void> response = validator.validateReportTemplateType(reportTemplateEntity);

        assertEquals(true, response.isLeft());
        assertEquals("ALL_FIELDS_MUST_BE_ACCUMULATED", response.getLeft().getTitle());
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

        when(field1.getDateRange()).thenReturn(ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END);
        when(field2.getDateRange()).thenReturn(ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END);
        when(field3.getDateRange()).thenReturn(ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END);

        Either<Problem, Void> response = validator.validateReportTemplateType(reportTemplateEntity);

        assertEquals(true, response.isRight());
    }
}
