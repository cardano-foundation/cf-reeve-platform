package org.cardanofoundation.lob.app.reporting.viewConverter.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;

@ExtendWith(MockitoExtension.class)
class IncomeStatementConverterTest {

    @Test
    void convertResponse_errorIsNotEmpty() {
        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.of(Problem.builder().build()));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        assertEquals(response, result);

    }

    @Test
    void convertResponse_aggregateValues() {
        ReportFieldDto child1 = new ReportFieldDto(3L, "Child 1", BigDecimal.TEN, List.of());
        ReportFieldDto field1 = new ReportFieldDto(1L, "Field 1", BigDecimal.ONE, List.of(child1));
        ReportFieldDto field2 = new ReportFieldDto(2L, "Field 2", BigDecimal.ONE, List.of());

        ReportResponseDto response = mock(ReportResponseDto.class);
        when(response.getError()).thenReturn(Optional.empty());
        when(response.getFields()).thenReturn(List.of(field1, field2));

        IncomeStatementConverter converter = new IncomeStatementConverter();
        ReportResponseDto result = converter.convertResponse(response);

        List<ReportFieldDto> resultFields = result.getFields();
        assertEquals(2, resultFields.size());
        assertEquals(BigDecimal.ONE, resultFields.getFirst().getValue());
        assertEquals(BigDecimal.valueOf(2), resultFields.get(1).getValue());
        List<ReportFieldDto> childFields = resultFields.getFirst().getChildFields();
        assertEquals(1, childFields.size());
        assertEquals(BigDecimal.TEN, childFields.getFirst().getValue());
    }

}
