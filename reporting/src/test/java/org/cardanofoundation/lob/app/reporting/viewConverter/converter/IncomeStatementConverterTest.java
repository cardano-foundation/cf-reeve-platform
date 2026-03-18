package org.cardanofoundation.lob.app.reporting.viewConverter.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;

@ExtendWith(MockitoExtension.class)
class IncomeStatementConverterTest {

    @Test
    void convertResponse_errorIsNotEmpty() {
        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.of(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST)));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        assertEquals(response, result);

    }

    @Test
    void convertResponse_aggregateValues() {
        ReportFieldDto child1 = new ReportFieldDto(3L, "Child 1", BigDecimal.TEN, List.of());
        ReportFieldDto field1 = new ReportFieldDto(1L, "Field 2", BigDecimal.ONE, List.of(child1));
        ReportFieldDto field3 = new ReportFieldDto(3L, "Field 3", BigDecimal.ONE, List.of());
        ReportFieldDto field4 = new ReportFieldDto(4L, "Field 4", BigDecimal.ONE, List.of());
        ReportFieldDto field2 = new ReportFieldDto(2L, "Field 5", BigDecimal.TWO, List.of(field3, field4));

        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.empty());
        when(response.getFields()).thenReturn(List.of(field1, field2));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        List<ReportFieldDto> resultFields = result.getFields();
        assertEquals(2, resultFields.size());
        assertEquals(BigDecimal.ONE, resultFields.getFirst().getValue());
        assertEquals(BigDecimal.valueOf(3), resultFields.get(1).getValue());
        List<ReportFieldDto> childFields = resultFields.getFirst().getChildFields();
        assertEquals(1, childFields.size());
        assertEquals(BigDecimal.TEN, childFields.getFirst().getValue());
    }

    @Test
    void convertResponse_doesNotRecurseIntoChildFields() {
        // grandchild under childA — old code would recurse and potentially alter child values
        ReportFieldDto grandchild = new ReportFieldDto(10L, "Grandchild", BigDecimal.ONE, List.of());
        ReportFieldDto childA = new ReportFieldDto(5L, "Child A", BigDecimal.valueOf(2), List.of(grandchild));
        ReportFieldDto field1 = new ReportFieldDto(1L, "Field 1", BigDecimal.valueOf(5), List.of(childA));

        // childC has a grandchild — old code recursed and accumulated childC as (childB + childC)
        ReportFieldDto grandchild2 = new ReportFieldDto(11L, "Grandchild 2", BigDecimal.valueOf(99), List.of());
        ReportFieldDto childB = new ReportFieldDto(6L, "Child B", BigDecimal.valueOf(3), List.of());
        ReportFieldDto childC = new ReportFieldDto(7L, "Child C", BigDecimal.valueOf(4), List.of(grandchild2));
        ReportFieldDto field2 = new ReportFieldDto(2L, "Field 2", BigDecimal.valueOf(10), List.of(childB, childC));

        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.empty());
        when(response.getFields()).thenReturn(List.of(field1, field2));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        List<ReportFieldDto> resultFields = result.getFields();
        // Top-level accumulation still works: field2 = field1 + field2 = 5 + 10 = 15
        assertEquals(BigDecimal.valueOf(5), resultFields.get(0).getValue());
        assertEquals(BigDecimal.valueOf(15), resultFields.get(1).getValue());

        // Child fields must NOT be recursively accumulated
        // childA is the only child of field1 (i=0), so no accumulation expected
        assertEquals(BigDecimal.valueOf(2), resultFields.get(0).getChildFields().get(0).getValue());
        // childB is the first child of field2, no accumulation
        assertEquals(BigDecimal.valueOf(3), resultFields.get(1).getChildFields().get(0).getValue());
        // childC has a grandchild — old code would have set childC = childB + childC = 3 + 4 = 7; new code leaves it as 4
        assertEquals(BigDecimal.valueOf(4), resultFields.get(1).getChildFields().get(1).getValue());
    }

    @Test
    void convertResponse_fieldsWithoutChildrenAreNotAccumulated() {
        ReportFieldDto field1 = new ReportFieldDto(1L, "Field 1", BigDecimal.valueOf(10), List.of());
        ReportFieldDto field2 = new ReportFieldDto(2L, "Field 2", BigDecimal.valueOf(20), List.of());
        ReportFieldDto field3 = new ReportFieldDto(3L, "Field 3", BigDecimal.valueOf(30), List.of());

        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.empty());
        when(response.getFields()).thenReturn(List.of(field1, field2, field3));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        List<ReportFieldDto> resultFields = result.getFields();
        // None have child fields, so the accumulation condition is never met — all values unchanged
        assertEquals(BigDecimal.valueOf(10), resultFields.get(0).getValue());
        assertEquals(BigDecimal.valueOf(20), resultFields.get(1).getValue());
        assertEquals(BigDecimal.valueOf(30), resultFields.get(2).getValue());
    }

}
