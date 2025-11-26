package org.cardanofoundation.lob.app.reporting.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.PublishError;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateFieldRepository;

@ExtendWith(MockitoExtension.class)
class ReportMapperTest {

    @Mock
    private ReportTemplateFieldRepository reportTemplateFieldRepository;

    @InjectMocks
    private ReportMapper reportMapper;

    @Test
    void toEntity_withNewReport_shouldCreateNewEntity() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template123")
                .name("Financial Report Template")
                .build();

        ReportDto dto = ReportDto.builder()
                .reportTemplateId("template123")
                .name("Q1 2024 Report")
                .intervalType("QUARTER")
                .period((short) 1)
                .year((short) 2024)
                .dataMode("SYSTEM")
                .fields(List.of())
                .build();
        dto.setOrganisationId("org123");

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result);
        assertEquals(template, result.getReportTemplate());
        assertEquals("org123", result.getOrganisationId());
        assertEquals("Q1 2024 Report", result.getName());
        assertEquals(IntervalType.QUARTER, result.getIntervalType());
        assertEquals((short) 1, result.getPeriod());
        assertEquals((short) 2024, result.getYear());
        assertEquals(DataMode.SYSTEM, result.getDataMode());
    }

    @Test
    void toEntity_withExistingReport_shouldUpdateExistingEntity() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template456")
                .name("Updated Template")
                .build();

        ReportEntity existingReport = ReportEntity.builder()
                .id("report456")
                .organisationId("org456")
                .name("Old Name")
                .intervalType(IntervalType.MONTH)
                .period((short) 3)
                .year((short) 2023)
                .dataMode(DataMode.USER)
                .fields(new ArrayList<>())
                .build();

        ReportDto dto = ReportDto.builder()
                .reportTemplateId("template456")
                .name("New Name")
                .intervalType("QUARTER")
                .period((short) 2)
                .year((short) 2024)
                .dataMode("SYSTEM")
                .fields(List.of())
                .build();
        dto.setOrganisationId("org456");
        // When
        ReportEntity result = reportMapper.toEntity(dto, existingReport, template);

        // Then
        assertSame(existingReport, result); // Same instance, not a new one
        assertEquals("New Name", result.getName());
        assertEquals(IntervalType.QUARTER, result.getIntervalType());
        assertEquals((short) 2, result.getPeriod());
        assertEquals((short) 2024, result.getYear());
        assertEquals(DataMode.SYSTEM, result.getDataMode());
    }

    @Test
    void toEntity_withFields_shouldMapFieldsCorrectly() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template789")
                .build();

        ReportTemplateFieldEntity templateField = ReportTemplateFieldEntity.builder()
                .id(100L)
                .name("Revenue")
                .build();

        ReportFieldDto fieldDto = ReportFieldDto.builder()
                .templateFieldId(100L)
                .value(new BigDecimal("50000.00"))
                .childFields(List.of())
                .build();

        ReportDto dto = ReportDto.builder()
                .reportTemplateId("template789")
                .name("Test Report")
                .intervalType("YEAR")
                .year((short) 2024)
                .dataMode("USER")
                .fields(List.of(fieldDto))
                .build();
        dto.setOrganisationId("org789");

        when(reportTemplateFieldRepository.findById(100L)).thenReturn(Optional.of(templateField));

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result.getFields());
        assertEquals(1, result.getFields().size());
        ReportFieldEntity field = result.getFields().get(0);
        assertEquals(new BigDecimal("50000.00"), field.getValue());
        assertEquals(templateField, field.getFieldTemplate());
        assertEquals(result, field.getReport());
        verify(reportTemplateFieldRepository).findById(100L);
    }

    @Test
    void toEntity_withNestedFields_shouldMapHierarchyCorrectly() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template999")
                .build();

        ReportTemplateFieldEntity parentTemplateField = ReportTemplateFieldEntity.builder()
                .id(200L)
                .name("Total Revenue")
                .build();

        ReportTemplateFieldEntity childTemplateField = ReportTemplateFieldEntity.builder()
                .id(201L)
                .name("Product Sales")
                .build();

        ReportFieldDto childFieldDto = ReportFieldDto.builder()
                .templateFieldId(201L)
                .value(new BigDecimal("30000.00"))
                .build();

        ReportFieldDto parentFieldDto = ReportFieldDto.builder()
                .templateFieldId(200L)
                .value(new BigDecimal("50000.00"))
                .childFields(List.of(childFieldDto))
                .build();

        ReportDto dto = ReportDto.builder()
                .name("Nested Report")
                .intervalType("QUARTER")
                .period((short) 1)
                .year((short) 2024)
                .dataMode("USER")
                .fields(List.of(parentFieldDto))
                .build();
        dto.setOrganisationId("org999");

        when(reportTemplateFieldRepository.findById(200L)).thenReturn(Optional.of(parentTemplateField));
        when(reportTemplateFieldRepository.findById(201L)).thenReturn(Optional.of(childTemplateField));

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result.getFields());
        assertEquals(1, result.getFields().size());

        ReportFieldEntity parentField = result.getFields().get(0);
        assertEquals(new BigDecimal("50000.00"), parentField.getValue());
        assertEquals(parentTemplateField, parentField.getFieldTemplate());

        assertNotNull(parentField.getChildFields());
        assertEquals(1, parentField.getChildFields().size());

        ReportFieldEntity childField = parentField.getChildFields().get(0);
        assertEquals(new BigDecimal("30000.00"), childField.getValue());
        assertEquals(childTemplateField, childField.getFieldTemplate());
        assertEquals(parentField, childField.getParentField());
        assertEquals(result, childField.getReport());
    }

    @Test
    void toEntity_withNullOptionalFields_shouldHandleGracefully() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template111")
                .build();

        ReportDto dto = ReportDto.builder()
                .name("Minimal Report")
                .dataMode("SYSTEM")
                .build();
        dto.setOrganisationId("org111");

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result);
        assertEquals("org111", result.getOrganisationId());
        assertEquals("Minimal Report", result.getName());
        assertNull(result.getIntervalType());
        assertEquals(DataMode.SYSTEM, result.getDataMode());
    }

    @Test
    void toResponseDto_withNullEntity_shouldReturnNull() {
        // When
        ReportResponseDto result = reportMapper.toResponseDto(null);

        // Then
        assertNull(result);
    }

    @Test
    void toResponseDto_withValidEntity_shouldMapToDto() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template222")
                .name("Template Name")
                .build();

        ReportEntity entity = ReportEntity.builder()
                .id("report222")
                .organisationId("org222")
                .reportTemplate(template)
                .name("Test Report")
                .intervalType(IntervalType.QUARTER)
                .period((short) 2)
                .year((short) 2024)
                .ver(1L)
                .dataMode(DataMode.USER)
                .isReadyToPublish(true)
                .ledgerDispatchApproved(false)
                .blockchainHash("hash123")
                .publishError(PublishError.INVALID_REPORT_DATA)
                .fields(new ArrayList<>())
                .build();

        // When
        ReportResponseDto result = reportMapper.toResponseDto(entity);

        // Then
        assertNotNull(result);
        assertEquals("report222", result.getId());
        assertEquals("org222", result.getOrganisationId());
        assertEquals("template222", result.getReportTemplateId());
        assertEquals("Test Report", result.getName());
        assertEquals("QUARTER", result.getIntervalType());
        assertEquals((short) 2, result.getPeriod());
        assertEquals((short) 2024, result.getYear());
        assertEquals(1L, result.getVer());
        assertEquals("USER", result.getDataMode());
        assertTrue(result.getIsReadyToPublish());
        assertFalse(result.getIsPublished());
        assertEquals("hash123", result.getBlockchainTxId());
        assertEquals("INVALID_REPORT_DATA", result.getPublishError());
        assertNotNull(result.getFields());
        assertTrue(result.getFields().isEmpty());
    }

    @Test
    void toResponseDto_withFieldsHierarchy_shouldMapOnlyTopLevelFields() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template333")
                .build();

        ReportTemplateFieldEntity parentTemplateField = ReportTemplateFieldEntity.builder()
                .id(300L)
                .name("Parent Field")
                .build();

        ReportTemplateFieldEntity childTemplateField = ReportTemplateFieldEntity.builder()
                .id(301L)
                .name("Child Field")
                .build();

        ReportEntity entity = ReportEntity.builder()
                .id("report333")
                .organisationId("org333")
                .reportTemplate(template)
                .name("Hierarchical Report")
                .intervalType(IntervalType.MONTH)
                .period((short) 5)
                .year((short) 2024)
                .dataMode(DataMode.USER)
                .fields(new ArrayList<>())
                .build();

        ReportFieldEntity childField = ReportFieldEntity.builder()
                .id(2L)
                .fieldTemplate(childTemplateField)
                .value(new BigDecimal("10000"))
                .childFields(new ArrayList<>())
                .build();

        ReportFieldEntity parentField = ReportFieldEntity.builder()
                .id(1L)
                .report(entity)
                .fieldTemplate(parentTemplateField)
                .value(new BigDecimal("20000"))
                .childFields(List.of(childField))
                .build();

        childField.setParentField(parentField);
        entity.getFields().add(parentField);
        entity.getFields().add(childField);

        // When
        ReportResponseDto result = reportMapper.toResponseDto(entity);

        // Then
        assertNotNull(result);
        assertNotNull(result.getFields());
        assertEquals(1, result.getFields().size()); // Only top-level fields

        ReportFieldDto topLevelFieldDto = result.getFields().get(0);
        assertEquals(300L, topLevelFieldDto.getTemplateFieldId());
        assertEquals("Parent Field", topLevelFieldDto.getTemplateFieldName());
        assertEquals(new BigDecimal("20000"), topLevelFieldDto.getValue());

        assertNotNull(topLevelFieldDto.getChildFields());
        assertEquals(1, topLevelFieldDto.getChildFields().size());

        ReportFieldDto childFieldDto = topLevelFieldDto.getChildFields().get(0);
        assertEquals(301L, childFieldDto.getTemplateFieldId());
        assertEquals("Child Field", childFieldDto.getTemplateFieldName());
        assertEquals(new BigDecimal("10000"), childFieldDto.getValue());
    }

    @Test
    void toResponseDto_withNullFields_shouldHandleGracefully() {
        // Given
        ReportEntity entity = ReportEntity.builder()
                .id("report444")
                .organisationId("org444")
                .name("Minimal Report")
                .fields(null)
                .build();

        // When
        ReportResponseDto result = reportMapper.toResponseDto(entity);

        // Then
        assertNotNull(result);
        assertEquals("report444", result.getId());
        assertNull(result.getReportTemplateId());
        assertNull(result.getIntervalType());
        assertNull(result.getDataMode());
        assertNull(result.getPublishError());
        assertNotNull(result.getFields());
        assertTrue(result.getFields().isEmpty());
    }

    @Test
    void toEntity_updatingExistingReportWithFields_shouldClearAndReplaceFields() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template555")
                .build();

        ReportTemplateFieldEntity templateField = ReportTemplateFieldEntity.builder()
                .id(500L)
                .name("New Field")
                .build();

        ReportFieldEntity oldField = ReportFieldEntity.builder()
                .id(99L)
                .value(new BigDecimal("999"))
                .build();

        ReportEntity existingReport = ReportEntity.builder()
                .id("report555")
                .fields(new ArrayList<>(List.of(oldField)))
                .build();

        ReportFieldDto newFieldDto = ReportFieldDto.builder()
                .templateFieldId(500L)
                .value(new BigDecimal("100"))
                .build();

        ReportDto dto = ReportDto.builder()
                .name("Updated Report")
                .intervalType("MONTH")
                .period((short) 6)
                .year((short) 2024)
                .dataMode("USER")
                .fields(List.of(newFieldDto))
                .build();
        dto.setOrganisationId("org555");

        when(reportTemplateFieldRepository.findById(500L)).thenReturn(Optional.of(templateField));

        // When
        ReportEntity result = reportMapper.toEntity(dto, existingReport, template);

        // Then
        assertSame(existingReport, result);
        assertNotNull(result.getFields());
        assertEquals(1, result.getFields().size());
        assertEquals(new BigDecimal("100"), result.getFields().get(0).getValue());
        assertEquals(templateField, result.getFields().get(0).getFieldTemplate());
        verify(reportTemplateFieldRepository).findById(500L);
    }

    @Test
    void toEntity_withFieldWithoutTemplateId_shouldCreateFieldWithoutTemplate() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template666")
                .build();

        ReportFieldDto fieldDto = ReportFieldDto.builder()
                .templateFieldId(null)
                .value(new BigDecimal("777"))
                .build();

        ReportDto dto = ReportDto.builder()
                .name("Custom Field Report")
                .dataMode("USER")
                .fields(List.of(fieldDto))
                .build();
        dto.setOrganisationId("org666");

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result.getFields());
        assertEquals(1, result.getFields().size());
        ReportFieldEntity field = result.getFields().get(0);
        assertEquals(new BigDecimal("777"), field.getValue());
        assertNull(field.getFieldTemplate());
        verifyNoInteractions(reportTemplateFieldRepository);
    }

    @Test
    void toResponseDto_withAllPublishStates_shouldMapCorrectly() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template777")
                .build();

        ReportEntity publishedReport = ReportEntity.builder()
                .id("report777")
                .organisationId("org777")
                .reportTemplate(template)
                .name("Published Report")
                .isReadyToPublish(true)
                .ledgerDispatchApproved(true)
                .blockchainHash("txhash456")
                .ledgerDispatchStatus(LedgerDispatchStatus.DISPATCHED)
                .fields(new ArrayList<>())
                .build();

        // When
        ReportResponseDto result = reportMapper.toResponseDto(publishedReport);

        // Then
        assertNotNull(result);
        assertTrue(result.getIsReadyToPublish());
        assertTrue(result.getIsPublished());
        assertEquals("txhash456", result.getBlockchainTxId());
    }

    @Test
    void toEntity_withMultipleFieldsAndNestedStructure_shouldMapAllCorrectly() {
        // Given
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template888")
                .build();

        ReportTemplateFieldEntity template1 = ReportTemplateFieldEntity.builder()
                .id(800L)
                .name("Field 1")
                .build();

        ReportTemplateFieldEntity template2 = ReportTemplateFieldEntity.builder()
                .id(801L)
                .name("Field 2")
                .build();

        ReportFieldDto childDto = ReportFieldDto.builder()
                .templateFieldId(801L)
                .value(new BigDecimal("5000"))
                .build();

        ReportFieldDto parentDto1 = ReportFieldDto.builder()
                .templateFieldId(800L)
                .value(new BigDecimal("10000"))
                .childFields(List.of(childDto))
                .build();

        ReportFieldDto parentDto2 = ReportFieldDto.builder()
                .templateFieldId(800L)
                .value(new BigDecimal("15000"))
                .build();

        ReportDto dto = ReportDto.builder()
                .name("Complex Report")
                .dataMode("USER")
                .fields(List.of(parentDto1, parentDto2))
                .build();
        dto.setOrganisationId("org888");

        when(reportTemplateFieldRepository.findById(800L)).thenReturn(Optional.of(template1));
        when(reportTemplateFieldRepository.findById(801L)).thenReturn(Optional.of(template2));

        // When
        ReportEntity result = reportMapper.toEntity(dto, null, template);

        // Then
        assertNotNull(result.getFields());
        assertEquals(2, result.getFields().size());

        // Verify first parent field has child
        ReportFieldEntity firstParent = result.getFields().get(0);
        assertEquals(new BigDecimal("10000"), firstParent.getValue());
        assertNotNull(firstParent.getChildFields());
        assertEquals(1, firstParent.getChildFields().size());
        assertEquals(new BigDecimal("5000"), firstParent.getChildFields().get(0).getValue());

        // Verify second parent field has no children
        ReportFieldEntity secondParent = result.getFields().get(1);
        assertEquals(new BigDecimal("15000"), secondParent.getValue());
        assertTrue(secondParent.getChildFields() == null || secondParent.getChildFields().isEmpty());
    }
}
