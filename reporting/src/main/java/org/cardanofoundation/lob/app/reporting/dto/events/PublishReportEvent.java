package org.cardanofoundation.lob.app.reporting.dto.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@RequiredArgsConstructor
@Getter
@Setter
public class PublishReportEvent {

    private String id;
    private String organisationId;
    private ReportTemplateType reportTemplateType;
    private Long reportTemplateVer;
    private Long reportVer;
    private IntervalType intervalType;
    private short period;
    private short year;
    private DataMode dataMode;
    private LedgerDispatchStatus dispatchStatus;
    private Map<String, Object> reportData;

    public static PublishReportEvent fromEntity(ReportEntity reportEntity) {
        PublishReportEvent event = new PublishReportEvent();
        event.setId(reportEntity.getId());
        event.setOrganisationId(reportEntity.getOrganisationId());
        event.setReportTemplateType(reportEntity.getReportTemplate().getReportTemplateType());
        event.setReportTemplateVer(reportEntity.getReportTemplate().getVer());
        event.setReportVer(reportEntity.getVer());
        event.setIntervalType(reportEntity.getIntervalType());
        event.setPeriod(reportEntity.getPeriod());
        event.setYear(reportEntity.getYear());
        event.setDataMode(reportEntity.getDataMode());
        event.setReportData(extractReportData(reportEntity.getFields(), new LinkedHashMap<>()));
        event.setDispatchStatus(reportEntity.getLedgerDispatchStatus());

        return event;
    }

    private static Map<String, Object> extractReportData(List<ReportFieldEntity> fields, Map<String, Object> reportData) {
        for (ReportFieldEntity field : fields) {
            if (field.getChildFields().isEmpty()) {
                // Leaf: {v: value, o: fieldOrder} — named keys remove ambiguity for consuming apps
                Map<String, Object> leaf = new LinkedHashMap<>(2);
                leaf.put("v", field.getValue());
                leaf.put("_o", field.getFieldTemplate().getFieldOrder());
                reportData.put(field.getFieldTemplate().getName(), leaf);
            } else {
                // Section: {_o: fieldOrder, ...children} — "_o" is the section's own order
                Map<String, Object> childData = new LinkedHashMap<>();
                childData.put("_o", field.getFieldTemplate().getFieldOrder());
                extractReportData(field.getChildFields(), childData);
                reportData.put(field.getFieldTemplate().getName(), childData);
            }
        }
        return reportData;
    }

}
