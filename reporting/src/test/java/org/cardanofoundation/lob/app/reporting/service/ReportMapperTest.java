package org.cardanofoundation.lob.app.reporting.service;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportMapper;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.PublishError;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateFieldRepository;
import org.cardanofoundation.lob.app.reporting.viewConverter.ReportResponseConverter;

@ExtendWith(MockitoExtension.class)
class ReportMapperTest {

    @Mock
    private ReportTemplateFieldRepository reportTemplateFieldRepository;
    @Mock
    private ReportTemplateMapper reportTemplateMapper;
    @Mock
    private ReportResponseConverter reportResponseConverter;

    @InjectMocks
    private ReportMapper reportMapper;

    @Test
    void toEntity_success() {
        ReportTemplateEntity templateEntity = mock(ReportTemplateEntity.class);

        ReportDto reportDto = ReportDto.builder()
                .name("Name123")
                .dataMode("SYSTEM")
                .intervalType("YEAR")
                .period((short) 1)
                .year((short) 2026)
                .fields(List.of(ReportFieldDto.builder()
                        .templateFieldId(123L)
                .value(BigDecimal.TEN)
                        .childFields(List.of(
                                ReportFieldDto.builder()
                                        .templateFieldId(456L)
                                        .value(BigDecimal.ONE)
                                        .build()))
                        .build()))
                .build();

        ReportEntity entity = reportMapper.toEntity(reportDto, null, templateEntity);

        assertEquals("Name123", entity.getName());
        assertEquals(1, entity.getFields().size());
        assertEquals(1, entity.getFields().getFirst().getChildFields().size());
        assertEquals(BigDecimal.ONE, entity.getFields().getFirst().getChildFields().getFirst().getValue());
        assertEquals(DataMode.SYSTEM, entity.getDataMode());
        assertEquals(IntervalType.YEAR, entity.getIntervalType());
        assertEquals(1, entity.getPeriod());
        assertEquals(2026, entity.getYear());
        assertEquals(templateEntity, entity.getReportTemplate());
    }

    @Test
    void toResponseDto() {
        ReportResponseDto responseDto = reportMapper.toResponseDto(null);
        assertNull(responseDto);

        // Setup Template
        ReportTemplateEntity template = mock(ReportTemplateEntity.class);
        ReportTemplateFieldEntity templateField1 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity templateField2 = mock(ReportTemplateFieldEntity.class);
        ReportTemplateFieldEntity childTemplateField = mock(ReportTemplateFieldEntity.class);

        // Setup Template Fields Structure
        // Field1 has a child
        when(templateField1.getId()).thenReturn(101L);
        when(templateField1.getName()).thenReturn("Field1");
        when(templateField1.getChildFields()).thenReturn(List.of(childTemplateField));

        when(childTemplateField.getId()).thenReturn(103L);
        when(childTemplateField.getName()).thenReturn("ChildField");
        when(childTemplateField.getChildFields()).thenReturn(Collections.emptyList());

        // Field2 is independent
        when(templateField2.getId()).thenReturn(102L);
        when(templateField2.getName()).thenReturn("Field2");
        when(templateField2.getChildFields()).thenReturn(Collections.emptyList());

        when(template.getId()).thenReturn("TemplateId");
        when(template.getReportTemplateType()).thenReturn(ReportTemplateType.BALANCE_SHEET);
        when(template.getFields()).thenReturn(List.of(templateField1, templateField2));

        // Setup Report Entity
        ReportEntity entity = new ReportEntity();
        entity.setId("ReportId");
        entity.setOrganisationId("OrgId");
        entity.setReportTemplate(template);
        entity.setName("ReportName");
        entity.setIntervalType(IntervalType.MONTH);
        entity.setPeriod((short) 5);
        entity.setYear((short) 2025);
        entity.setVer((short) 1);
        entity.setDataMode(DataMode.USER);
        entity.setReadyToPublish(true);
        entity.setLedgerDispatchApproved(false);
        entity.setBlockchainHash("tx123");
        entity.setLedgerDispatchStatus(LedgerDispatchStatus.DISPATCHED);
        entity.setPublishError(PublishError.INVALID_REPORT_DATA);
        entity.setCreatedAt(LocalDateTime.of(2025, 1, 1, 10, 0));
        entity.setCreatedBy("Creator");
        entity.setUpdatedAt(LocalDateTime.of(2025, 1, 2, 10, 0));
        entity.setUpdatedBy("Updater");
        entity.setLedgerDispatchDate(LocalDateTime.of(2025, 1, 3, 10, 0));
        entity.setPublishedBy("Publisher");

        // Setup Report Fields
        // We only provide value for Field1 and its child. Field2 is missing and should be filled with 0.
        ReportFieldEntity field1 = new ReportFieldEntity();
        field1.setFieldTemplate(templateField1);
        // Value is calculated from children if any exist

        ReportFieldEntity childField = new ReportFieldEntity();
        childField.setFieldTemplate(childTemplateField);
        childField.setValue(BigDecimal.ONE);
        childField.setParentField(field1);
        field1.setChildFields(List.of(childField));

        entity.setFields(new ArrayList<>(List.of(field1)));

        // Setup Failed Validation Rules
        ReportTemplateValidationRuleEntity rule = mock(ReportTemplateValidationRuleEntity.class);
        entity.setFailedValidationRules(List.of(rule));

        // Mock converter to just return the dto passed to it
        when(reportResponseConverter.convertResponse(any(ReportResponseDto.class), any(ReportTemplateType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Execute
        ReportResponseDto result = reportMapper.toResponseDto(entity);

        // Assertions
        assertNotNull(result);
        assertEquals("ReportId", result.getId());
        assertEquals("OrgId", result.getOrganisationId());
        assertEquals("TemplateId", result.getReportTemplateId());
        assertEquals(ReportTemplateType.BALANCE_SHEET, result.getReportTemplateType());
        assertEquals("ReportName", result.getName());
        assertEquals("MONTH", result.getIntervalType());
        assertEquals((short) 5, result.getPeriod());
        assertEquals((short) 2025, result.getYear());
        assertEquals((short) 1, result.getVer());
        assertEquals("USER", result.getDataMode());
        assertEquals(true, result.getIsReadyToPublish());
        assertEquals(false, result.getIsPublished()); // checks ledgerDispatchApproved
        assertEquals("tx123", result.getBlockchainTxId());
        assertEquals(LedgerDispatchStatus.DISPATCHED, result.getLedgerDispatchStatus());
        assertEquals("INVALID_REPORT_DATA", result.getPublishError());

        // Check fields
        assertEquals(2, result.getFields().size());

        // Check Field1 (exists)
        ReportFieldDto resultField1 = result.getFields().stream().filter(f -> "Field1".equals(f.getTemplateFieldName())).findFirst().orElseThrow();
        assertEquals(BigDecimal.ONE, resultField1.getValue());
        assertEquals(1, resultField1.getChildFields().size());
        assertEquals("ChildField", resultField1.getChildFields().getFirst().getTemplateFieldName());
        assertEquals(BigDecimal.ONE, resultField1.getChildFields().getFirst().getValue());

        // Check Field2 (should be filled with zero)
        ReportFieldDto resultField2 = result.getFields().stream().filter(f -> "Field2".equals(f.getTemplateFieldName())).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, resultField2.getValue());
    }

}
