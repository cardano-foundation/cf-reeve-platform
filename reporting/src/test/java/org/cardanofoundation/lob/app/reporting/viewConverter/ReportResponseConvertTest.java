package org.cardanofoundation.lob.app.reporting.viewConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class ReportResponseConvertTest {

    @Test
    void convertResponse_NoConverterFound() {
        ReportResponseConverter converter = new ReportResponseConverter(List.of());

        ReportResponseDto dto = mock(ReportResponseDto.class);

        ReportResponseDto result = converter.convertResponse(dto, ReportTemplateType.BALANCE_SHEET);
        assertEquals(dto, result);
    }

    @Test
    void convertResponse_ConverterFound() {
        ResponseConverter converter = mock(ResponseConverter.class);
        ReportResponseDto dto = mock(ReportResponseDto.class);

        when(converter.getSupportedReportTemplateType()).thenReturn(ReportTemplateType.BALANCE_SHEET);
        when(converter.convertResponse(any(ReportResponseDto.class))).thenReturn(dto);

        ReportResponseConverter reportResponseConverter = new ReportResponseConverter(List.of(converter));
        ReportResponseDto result = reportResponseConverter.convertResponse(dto, ReportTemplateType.BALANCE_SHEET);
        assertEquals(dto, result);
    }
}
