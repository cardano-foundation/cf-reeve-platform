package org.cardanofoundation.lob.app.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@ExtendWith(MockitoExtension.class)
class ReportingTemplateMapperTest {

    @Mock
    private ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    @Mock
    private ReportingRepository reportingRepository;

    private ReportTemplateMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ReportTemplateMapper(chartOfAccountSubTypeRepository, reportingRepository);
    }

    @Test
    void toEntity_shouldCreateNewEntityWhenExistingTemplateIsNull() {
        // Given
        ReportTemplateDto dto = ReportTemplateDto.builder()

            .name("Test Template")
            .description("Test Description")
            .reportTemplateType("BALANCE_SHEET")
            .fields(Collections.emptyList())
            .dataMode("SYSTEM")
            .build();
        dto.setOrganisationId("org123");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getOrganisationId()).isEqualTo("org123");
        assertThat(result.getName()).isEqualTo("Test Template");
        assertThat(result.getDescription()).isEqualTo("Test Description");
        assertThat(result.getReportTemplateType()).isEqualTo(ReportTemplateType.BALANCE_SHEET);
        assertThat(result.getFields()).isEmpty();
    }

    @Test
    void toEntity_shouldUpdateExistingEntityWhenProvided() {
        // Given
        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
            .id("existing-id")
            .organisationId("old-org")
            .name("Old Name")
            .ver(5L)
            .fields(new ArrayList<>())
            .build();

        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("New Name")
            .description("New Description")
            .reportTemplateType("INCOME_STATEMENT")
            .fields(Collections.emptyList())
            .dataMode("SYSTEM")
            .build();
        dto.setOrganisationId("new-org");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, existingTemplate);

        // Then
        assertThat(result).isSameAs(existingTemplate);
        assertThat(result.getId()).isEqualTo("existing-id");
        assertThat(result.getOrganisationId()).isEqualTo("new-org");
        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getDescription()).isEqualTo("New Description");
        assertThat(result.getReportTemplateType()).isEqualTo(ReportTemplateType.INCOME_STATEMENT);
        assertThat(result.getVer()).isEqualTo(5L); // Version should remain unchanged
    }

    @Test
    void toEntity_shouldMapFieldsWithoutMappingTypes() {
        // Given
        ReportTemplateFieldDto fieldDto = ReportTemplateFieldDto.builder()
            .fieldName("Revenue")
            .accumulated(true)
            .accumulatedYearly(false)
            .accumulatedPreviousYear(false)
            .negated(false)
            .build();

        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("Test Template")
            .reportTemplateType("BALANCE_SHEET")
            .fields(Collections.singletonList(fieldDto))
            .dataMode("SYSTEM")
            .build();
        dto.setOrganisationId("org123");
        // When
        ReportTemplateEntity result = mapper.toEntity(dto, null);

        // Then
        assertThat(result.getFields()).hasSize(1);
        ReportTemplateFieldEntity field = result.getFields().get(0);
        assertThat(field.getName()).isEqualTo("Revenue");
        assertThat(field.isAccumulated()).isTrue();
        assertThat(field.isAccumulatedYearly()).isFalse();
        assertThat(field.isNegated()).isFalse();
        assertThat(field.getMappingTypes()).isEmpty();
        assertThat(field.getReportTemplate()).isSameAs(result);
        assertThat(field.getParentField()).isNull();
    }

    @Test
    void toEntity_shouldMapFieldsWithMappingTypes() {
        // Given
        ChartOfAccountSubType subType1 = ChartOfAccountSubType.builder()
            .id(1L)
            .name("SubType1")
            .build();
        ChartOfAccountSubType subType2 = ChartOfAccountSubType.builder()
            .id(2L)
            .name("SubType2")
            .build();

        when(chartOfAccountSubTypeRepository.findAllById(anyList()))
            .thenReturn(Arrays.asList(subType1, subType2));

        ReportTemplateFieldDto fieldDto = ReportTemplateFieldDto.builder()
            .fieldName("Assets")
            .mappingSubTypeIds(Arrays.asList(1L, 2L))
            .build();

        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("Test Template")
            .reportTemplateType("BALANCE_SHEET")
            .dataMode("SYSTEM")
            .fields(Collections.singletonList(fieldDto))
            .build();
        dto.setOrganisationId("org123");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, null);

        // Then
        assertThat(result.getFields()).hasSize(1);
        ReportTemplateFieldEntity field = result.getFields().get(0);
        assertThat(field.getMappingTypes()).hasSize(2);
        assertThat(field.getMappingTypes()).containsExactly(subType1, subType2);
    }

    @Test
    void toEntity_shouldMapHierarchicalFields() {
        // Given
        ReportTemplateFieldDto childFieldDto = ReportTemplateFieldDto.builder()
            .fieldName("Current Assets")
            .accumulated(false)
            .build();

        ReportTemplateFieldDto parentFieldDto = ReportTemplateFieldDto.builder()
            .fieldName("Total Assets")
            .accumulated(true)
            .childFields(Collections.singletonList(childFieldDto))
            .build();

        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("Test Template")
            .dataMode("SYSTEM")
            .reportTemplateType("BALANCE_SHEET")
            .fields(Collections.singletonList(parentFieldDto))
            .build();
        dto.setOrganisationId("org123");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, null);

        // Then
        assertThat(result.getFields()).hasSize(1);
        ReportTemplateFieldEntity parentField = result.getFields().get(0);
        assertThat(parentField.getName()).isEqualTo("Total Assets");
        assertThat(parentField.getChildFields()).hasSize(1);

        ReportTemplateFieldEntity childField = parentField.getChildFields().get(0);
        assertThat(childField.getName()).isEqualTo("Current Assets");
        assertThat(childField.getParentField()).isSameAs(parentField);
        assertThat(childField.getReportTemplate()).isSameAs(result);
    }

    @Test
    void toEntity_shouldClearAndUpdateExistingColumns() {
        // Given
        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
            .organisationId("org123")
            .name("Template")
            .fields(new ArrayList<>())
            .build();

        // Add an old field to the existing template
        ReportTemplateFieldEntity oldField = ReportTemplateFieldEntity.builder()
            .name("Old Field")
            .reportTemplate(existingTemplate)
            .childFields(new ArrayList<>())
            .mappingTypes(new ArrayList<>())
            .build();
        existingTemplate.getFields().add(oldField);

        ReportTemplateFieldDto newFieldDto = ReportTemplateFieldDto.builder()
            .fieldName("New Field")
            .build();

        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("Template")
            .dataMode("SYSTEM")
            .reportTemplateType("BALANCE_SHEET")
            .fields(Collections.singletonList(newFieldDto))
            .build();
        dto.setOrganisationId("org123");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, existingTemplate);

        // Then
        assertThat(result.getFields()).hasSize(1);
        assertThat(result.getFields().get(0).getName()).isEqualTo("New Field");
    }

    @Test
    void toResponseDto_shouldReturnNullWhenEntityIsNull() {
        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(null);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void toResponseDto_shouldMapEntityToDto() {
        // Given
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
            .id("template-123")
            .organisationId("org456")
            .name("Financial Report")
            .description("Annual financial report")
            .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
            .ver(3L)
            .fields(new ArrayList<>())
            .build();

        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(entity);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("template-123");
        assertThat(result.getOrganisationId()).isEqualTo("org456");
        assertThat(result.getName()).isEqualTo("Financial Report");
        assertThat(result.getDescription()).isEqualTo("Annual financial report");
        assertThat(result.getReportTemplateType()).isEqualTo(ReportTemplateType.BALANCE_SHEET);
        assertThat(result.getVer()).isEqualTo(3L);
        assertThat(result.getColumns()).isEmpty();
    }

    @Test
    void toResponseDto_shouldMapFieldsWithoutChildren() {
        // Given
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
            .id("template-123")
            .organisationId("org456")
            .name("Test")
            .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
            .ver(1L)
            .fields(new ArrayList<>())
            .build();

        ChartOfAccountSubType subType = ChartOfAccountSubType.builder()
            .id(10L)
            .name("SubType")
            .build();

        ReportTemplateFieldEntity field = ReportTemplateFieldEntity.builder()
            .id(100L)
            .name("Revenue")
            .accumulated(true)
            .accumulatedYearly(true)
            .accumulatedPreviousYear(false)
            .negated(true)
            .reportTemplate(entity)
            .mappingTypes(Collections.singletonList(subType))
            .childFields(new ArrayList<>())
            .build();

        entity.getFields().add(field);

        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(entity);

        // Then
        assertThat(result.getColumns()).hasSize(1);
        ReportTemplateFieldDto fieldDto = result.getColumns().get(0);
        assertThat(fieldDto.getId()).isEqualTo(100L);
        assertThat(fieldDto.getFieldName()).isEqualTo("Revenue");
        assertThat(fieldDto.isAccumulated()).isTrue();
        assertThat(fieldDto.isAccumulatedYearly()).isTrue();
        assertThat(fieldDto.isAccumulatedPreviousYear()).isFalse();
        assertThat(fieldDto.isNegated()).isTrue();
        assertThat(fieldDto.getMappingSubTypeIds()).containsExactly(10L);
        assertThat(fieldDto.getChildFields()).isEmpty();
    }

    @Test
    void toResponseDto_shouldMapHierarchicalFields() {
        // Given
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
            .id("template-123")
            .organisationId("org456")
            .name("Test")
            .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
            .ver(1L)
            .fields(new ArrayList<>())
            .build();

        ReportTemplateFieldEntity parentField = ReportTemplateFieldEntity.builder()
            .id(1L)
            .name("Parent Field")
            .reportTemplate(entity)
            .childFields(new ArrayList<>())
            .mappingTypes(new ArrayList<>())
            .build();

        ReportTemplateFieldEntity childField = ReportTemplateFieldEntity.builder()
            .id(2L)
            .name("Child Field")
            .parentField(parentField)
            .reportTemplate(entity)
            .childFields(new ArrayList<>())
            .mappingTypes(new ArrayList<>())
            .build();

        parentField.getChildFields().add(childField);
        entity.getFields().add(parentField);

        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(entity);

        // Then
        assertThat(result.getColumns()).hasSize(1);
        ReportTemplateFieldDto parentDto = result.getColumns().get(0);
        assertThat(parentDto.getFieldName()).isEqualTo("Parent Field");
        assertThat(parentDto.getChildFields()).hasSize(1);

        ReportTemplateFieldDto childDto = parentDto.getChildFields().get(0);
        assertThat(childDto.getFieldName()).isEqualTo("Child Field");
        assertThat(childDto.getChildFields()).isEmpty();
    }

    @Test
    void toResponseDto_shouldOnlyMapTopLevelFields() {
        // Given
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
            .id("template-123")
            .organisationId("org456")
            .name("Test")
            .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
            .ver(1L)
            .fields(new ArrayList<>())
            .build();

        ReportTemplateFieldEntity topLevelField = ReportTemplateFieldEntity.builder()
            .id(1L)
            .name("Top Level")
            .reportTemplate(entity)
            .parentField(null)
            .childFields(new ArrayList<>())
            .mappingTypes(new ArrayList<>())
            .build();

        ReportTemplateFieldEntity childField = ReportTemplateFieldEntity.builder()
            .id(2L)
            .name("Child")
            .reportTemplate(entity)
            .parentField(topLevelField)
            .childFields(new ArrayList<>())
            .mappingTypes(new ArrayList<>())
            .build();

        // Add both to the columns list (simulating what JPA might do)
        entity.getFields().add(topLevelField);
        entity.getFields().add(childField);

        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(entity);

        // Then
        // Only the top-level field should be in the result's columns
        assertThat(result.getColumns()).hasSize(1);
        assertThat(result.getColumns().get(0).getFieldName()).isEqualTo("Top Level");
    }

    @Test
    void toEntity_shouldHandleNullFields() {
        // Given
        ReportTemplateDto dto = ReportTemplateDto.builder()
            .name("Test Template")
            .reportTemplateType("BALANCE_SHEET")
            .fields(null)
            .dataMode("SYSTEM")
            .build();
        dto.setOrganisationId("org123");

        // When
        ReportTemplateEntity result = mapper.toEntity(dto, null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getFields()).isEmpty();
    }

    @Test
    void toResponseDto_shouldHandleNullColumns() {
        // Given
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
            .id("template-123")
            .organisationId("org456")
            .name("Test")
            .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
            .ver(1L)
            .fields(null)
            .build();

        // When
        ReportTemplateResponseDto result = mapper.toResponseDto(entity);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getColumns()).isEmpty();
    }
}
