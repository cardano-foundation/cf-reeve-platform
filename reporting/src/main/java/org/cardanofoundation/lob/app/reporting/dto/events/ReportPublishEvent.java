package org.cardanofoundation.lob.app.reporting.dto.events;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@RequiredArgsConstructor
@Getter
@Setter
public class ReportPublishEvent {

    private String id;
    private String organisationId;
    private ReportTemplateType reportTemplateType;
    private Long reportTemplateVer;
    private Long reportVer;
    private IntervalType intervalType;
    private short period;
    private short year;
    private DataMode dataMode;
    private Map<String, Object> reportData;

    public static ReportPublishEvent fromEntity(ReportEntity reportEntity) {
        ReportPublishEvent event = new ReportPublishEvent();
        event.setId(reportEntity.getId());
        event.setOrganisationId(reportEntity.getOrganisationId());
        event.setReportTemplateType(reportEntity.getReportTemplate().getReportTemplateType());
        event.setReportTemplateVer(reportEntity.getReportTemplate().getVer());
        event.setReportVer(reportEntity.getVer());
        event.setIntervalType(reportEntity.getIntervalType());
        event.setPeriod(reportEntity.getPeriod());
        event.setYear(reportEntity.getYear());
        event.setDataMode(reportEntity.getDataMode());
        event.setReportData(extractReportData(reportEntity.getFields(), new HashMap<>()));

        return event;
    }

    private static Map<String, Object> extractReportData(List<ReportFieldEntity> fields, Map<String, Object> reportData) {
        for (ReportFieldEntity field : fields) {
            if(field.getChildFields().isEmpty()) {
                reportData.put(field.getFieldTemplate().getName(), field.getValue());
            } else {
                Map<String, Object> childData = new HashMap<>();
                extractReportData(field.getChildFields(), childData);
                reportData.put(field.getFieldTemplate().getName(), childData);
            }
        }
        return reportData;
    }

}
