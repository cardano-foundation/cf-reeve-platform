package org.cardanofoundation.lob.app.reporting.viewConverter;

import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

public interface ResponseConverter {

    ReportTemplateType getSupportedReportTemplateType();
    ReportResponseDto convertResponse(ReportResponseDto reportData);

}
