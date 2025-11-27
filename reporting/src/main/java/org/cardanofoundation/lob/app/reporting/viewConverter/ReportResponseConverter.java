package org.cardanofoundation.lob.app.reporting.viewConverter;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportResponseConverter {

    private final List<ResponseConverter> responseConverters;

    public ReportResponseDto convertResponse(ReportResponseDto reportData, ReportTemplateType type) {
        Optional<ResponseConverter> converter = responseConverters.stream().filter(c -> c.getSupportedReportTemplateType().equals(type)).findFirst();
        if(converter.isPresent()) {
            return converter.get().convertResponse(reportData);
        } else {
            log.debug("No response converter found for report template type: {}", type);
            return reportData;
        }
    }
}
