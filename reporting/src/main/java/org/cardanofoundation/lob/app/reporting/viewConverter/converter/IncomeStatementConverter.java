package org.cardanofoundation.lob.app.reporting.viewConverter.converter;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.viewConverter.ResponseConverter;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncomeStatementConverter implements ResponseConverter {


    @Override
    public ReportTemplateType getSupportedReportTemplateType() {
        return ReportTemplateType.INCOME_STATEMENT;
    }

    @Override
    public ReportResponseDto convertResponse(ReportResponseDto reportData) {
        if(reportData.getError().isEmpty()) {
            reportData.setFields(convertFields(reportData.getFields()));
        }
        return reportData;
    }

    private List<ReportFieldDto> convertFields(List<ReportFieldDto> fields) {
        for (int i = 0; i < fields.size(); i++) {
            ReportFieldDto field = fields.get(i);
            if(i > 0 && !field.getChildFields().isEmpty()) {
                field.setValue(fields.get(i-1).getValue().add(field.getValue()));
            }
            // Recursively convert child fields
            if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
                field.setChildFields(convertFields(field.getChildFields()));
            }
        }
        return fields;
    }
}
