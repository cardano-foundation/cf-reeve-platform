package org.cardanofoundation.lob.app.reporting.dto;

import java.time.LocalDate;
import java.util.List;

import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

public record ReportTemplateFilter(
        String name,
        List<ReportTemplateType> reportTemplateTypes,
        Boolean active,
        List<DataMode> dataMode,
        LocalDate dateFrom,
        LocalDate dateTo
) {}
