package org.cardanofoundation.lob.app.reporting.dto.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

class PublishReportEventTest {

    @Test
    void fromEntity_extractsBasicReportInfo() {
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .ver(1L)
                .dataMode(DataMode.SYSTEM)
                .build();

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-id");
        reportEntity.setOrganisationId("org-id");
        reportEntity.setReportTemplate(template);
        reportEntity.setVer(2L);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setPeriod((short) 1);
        reportEntity.setYear((short) 2024);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        reportEntity.setFields(new ArrayList<>());

        PublishReportEvent event = PublishReportEvent.fromEntity(reportEntity);

        assertThat(event.getId()).isEqualTo("report-id");
        assertThat(event.getOrganisationId()).isEqualTo("org-id");
        assertThat(event.getReportTemplateType()).isEqualTo(ReportTemplateType.BALANCE_SHEET);
        assertThat(event.getReportTemplateVer()).isEqualTo(1L);
        assertThat(event.getReportVer()).isEqualTo(2L);
        assertThat(event.getIntervalType()).isEqualTo(IntervalType.YEAR);
        assertThat(event.getPeriod()).isEqualTo((short) 1);
        assertThat(event.getYear()).isEqualTo((short) 2024);
        assertThat(event.getDataMode()).isEqualTo(DataMode.SYSTEM);
        assertThat(event.getDispatchStatus()).isEqualTo(LedgerDispatchStatus.NOT_DISPATCHED);
    }

    @Test
    void fromEntity_extractsReportDataWithFieldOrder() {
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .ver(1L)
                .dataMode(DataMode.SYSTEM)
                .build();

        ReportTemplateFieldEntity field1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("Cash")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.PERIOD)
                .build();

        ReportFieldEntity reportField1 = new ReportFieldEntity();
        reportField1.setValue(BigDecimal.valueOf(1000));
        reportField1.setFieldTemplate(field1);

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-id");
        reportEntity.setOrganisationId("org-id");
        reportEntity.setReportTemplate(template);
        reportEntity.setVer(2L);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setPeriod((short) 1);
        reportEntity.setYear((short) 2024);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        reportEntity.setFields(List.of(reportField1));

        PublishReportEvent event = PublishReportEvent.fromEntity(reportEntity);

        assertThat(event.getReportData()).isNotNull().containsKey("Cash");
    }

    @Test
    void fromEntity_extractsNestedReportDataWithFieldOrder() {
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .ver(1L)
                .dataMode(DataMode.SYSTEM)
                .build();

        ReportTemplateFieldEntity parentField = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("Assets")
                .fieldOrder(0)
                .dateRange(ReportFieldDateRange.PERIOD)
                .build();

        ReportTemplateFieldEntity childField = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("CurrentAssets")
                .fieldOrder(0)
                .parentField(parentField)
                .dateRange(ReportFieldDateRange.PERIOD)
                .build();

        ReportFieldEntity parentReportField = new ReportFieldEntity();
        parentReportField.setFieldTemplate(parentField);
        parentReportField.setChildFields(new ArrayList<>());

        ReportFieldEntity childReportField = new ReportFieldEntity();
        childReportField.setValue(BigDecimal.valueOf(5000));
        childReportField.setFieldTemplate(childField);
        childReportField.setParentField(parentReportField);

        parentReportField.getChildFields().add(childReportField);

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-id");
        reportEntity.setOrganisationId("org-id");
        reportEntity.setReportTemplate(template);
        reportEntity.setVer(2L);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setPeriod((short) 1);
        reportEntity.setYear((short) 2024);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        reportEntity.setFields(List.of(parentReportField));

        PublishReportEvent event = PublishReportEvent.fromEntity(reportEntity);

        assertThat(event.getReportData()).isNotNull().containsKey("Assets");
        @SuppressWarnings("unchecked")
        Map<String, Object> assetsMap = (Map<String, Object>) event.getReportData().get("Assets");
        assertThat(assetsMap).containsEntry("_o", 0).containsKey("CurrentAssets");
    }

    @Test
    void fromEntity_handlesEmptyFields() {
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .ver(1L)
                .dataMode(DataMode.SYSTEM)
                .build();

        ReportEntity reportEntity = new ReportEntity();
        reportEntity.setId("report-id");
        reportEntity.setOrganisationId("org-id");
        reportEntity.setReportTemplate(template);
        reportEntity.setVer(2L);
        reportEntity.setIntervalType(IntervalType.YEAR);
        reportEntity.setPeriod((short) 1);
        reportEntity.setYear((short) 2024);
        reportEntity.setDataMode(DataMode.SYSTEM);
        reportEntity.setLedgerDispatchStatus(LedgerDispatchStatus.NOT_DISPATCHED);
        reportEntity.setFields(new ArrayList<>());

        PublishReportEvent event = PublishReportEvent.fromEntity(reportEntity);

        assertThat(event.getReportData()).isNotNull().isEmpty();
    }
}
