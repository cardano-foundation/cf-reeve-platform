package org.cardanofoundation.lob.app.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;

@ExtendWith(MockitoExtension.class)
class ReportTemplateMapperTest {

    @Mock
    private ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;

    @InjectMocks
    private ReportTemplateMapper mapper;

    @Test
    void toEntity_addValidationRules() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field = mock(ReportTemplateFieldDto.class);
        ValidationRuleDto validationRuleDto = mock(ValidationRuleDto.class);
        ValidationRuleTermDto termDto = mock(ValidationRuleTermDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getValidationRules()).thenReturn(List.of(validationRuleDto));
        when(dto.getFields()).thenReturn(List.of(field));
        when(field.getFieldName()).thenReturn("field1");
        when(validationRuleDto.getName()).thenReturn("rule-name");
        when(validationRuleDto.getOperator()).thenReturn("EQUAL");
        when(termDto.getFieldName()).thenReturn("field1");
        when(termDto.getOperation()).thenReturn("ADD");
        when(validationRuleDto.getLeftSideTerms()).thenReturn(List.of(termDto));
        when(validationRuleDto.getRightSideTerms()).thenReturn(List.of(termDto));

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertEquals("org-id", entity.getOrganisationId());
        assertEquals("name", entity.getName());
        assertEquals("description", entity.getDescription());
        assertEquals("BALANCE_SHEET", entity.getReportTemplateType().name());
        assertTrue(entity.isActive());
        assertEquals(1, entity.getValidationRules().size());
        ReportTemplateValidationRuleEntity ruleEntity = entity.getValidationRules().get(0);
        assertEquals("rule-name", ruleEntity.getName());
        assertEquals("EQUAL", ruleEntity.getOperator().name());
        assertEquals(2, ruleEntity.getTerms().size());
    }

    @Test
    void toResponseDto_mapsAuditFields() {
        LocalDateTime createdAt = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2024, 4, 8, 14, 0, 0);

        ReportTemplateEntity entity = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .build();
        entity.setCreatedBy("john.doe@example.com");
        entity.setUpdatedBy("jane.smith@example.com");
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        ReportTemplateResponseDto dto = mapper.toResponseDto(entity);

        assertThat(dto.getCreatedBy()).isEqualTo("john.doe@example.com");
        assertThat(dto.getUpdatedBy()).isEqualTo("jane.smith@example.com");
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dto.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void toResponseDto_withNullAuditFields_mapsToNull() {
        ReportTemplateEntity entity = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.INCOME_STATEMENT)
                .dataMode(DataMode.USER)
                .build();

        ReportTemplateResponseDto dto = mapper.toResponseDto(entity);

        assertNull(dto.getCreatedBy());
        assertNull(dto.getUpdatedBy());
        assertNull(dto.getCreatedAt());
        assertNull(dto.getUpdatedAt());
    }

    @Test
    void toResponseDto_withNullEntity_returnsNull() {
        assertNull(mapper.toResponseDto(null));
    }

    @Test
    void toResponseDto_mapsAllBaseFields() {
        LocalDateTime createdAt = LocalDateTime.of(2025, 6, 1, 9, 0, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2025, 10, 20, 12, 0, 0);

        ReportTemplateEntity entity = ReportTemplateEntity.builder()
                .id("template-abc")
                .organisationId("org-xyz")
                .name("Full Template")
                .description("A full test template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .editable(false)
                .reportCount(5)
                .build();
        entity.setCreatedBy("creator@example.com");
        entity.setUpdatedBy("updater@example.com");
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(updatedAt);

        ReportTemplateResponseDto dto = mapper.toResponseDto(entity);

        assertThat(dto.getId()).isEqualTo("template-abc");
        assertThat(dto.getOrganisationId()).isEqualTo("org-xyz");
        assertThat(dto.getName()).isEqualTo("Full Template");
        assertThat(dto.getDescription()).isEqualTo("A full test template");
        assertThat(dto.getReportTemplateType()).isEqualTo(ReportTemplateType.BALANCE_SHEET);
        assertThat(dto.getDataMode()).isEqualTo("SYSTEM");
        assertThat(dto.getActive()).isTrue();
        assertThat(dto.getEditable()).isFalse();
        assertThat(dto.getReportCount()).isEqualTo(5);
        assertThat(dto.getCreatedBy()).isEqualTo("creator@example.com");
        assertThat(dto.getUpdatedBy()).isEqualTo("updater@example.com");
        assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
        assertThat(dto.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void toEntity_setsFieldOrderForNewFields() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field3 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1, field2, field3));
        when(field1.getFieldName()).thenReturn("field1");
        when(field2.getFieldName()).thenReturn("field2");
        when(field3.getFieldName()).thenReturn("field3");
        when(field1.getAccounts()).thenReturn(null);
        when(field2.getAccounts()).thenReturn(null);
        when(field3.getAccounts()).thenReturn(null);
        when(field1.getChildFields()).thenReturn(null);
        when(field2.getChildFields()).thenReturn(null);
        when(field3.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(3);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
        assertThat(entity.getFields().get(2).getFieldOrder()).isEqualTo(2);
    }

    @Test
    void toEntity_updatesFieldOrderWhenMergingExistingFields() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field2, field1)); // Reversed order
        when(field1.getFieldName()).thenReturn("field1");
        when(field2.getFieldName()).thenReturn("field2");
        when(field1.getAccounts()).thenReturn(null);
        when(field2.getAccounts()).thenReturn(null);
        when(field1.getChildFields()).thenReturn(null);
        when(field2.getChildFields()).thenReturn(null);

        // Create existing template with fields in different order
        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField2 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("field2")
                .fieldOrder(1)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1, existingField2)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getName()).isEqualTo("field2");
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getName()).isEqualTo("field1");
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toEntity_setsFieldOrderForChildFields() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField2 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentField));
        when(parentField.getFieldName()).thenReturn("parent");
        when(parentField.getAccounts()).thenReturn(null);
        when(parentField.getChildFields()).thenReturn(List.of(childField1, childField2));
        when(childField1.getFieldName()).thenReturn("child1");
        when(childField2.getFieldName()).thenReturn("child2");
        when(childField1.getAccounts()).thenReturn(null);
        when(childField2.getAccounts()).thenReturn(null);
        when(childField1.getChildFields()).thenReturn(null);
        when(childField2.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getChildFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(0).getChildFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toEntity_preservesFieldOrderInNestedStructures() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto grandChildField1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto grandChildField2 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentField));
        when(parentField.getFieldName()).thenReturn("parent");
        when(parentField.getAccounts()).thenReturn(null);
        when(parentField.getChildFields()).thenReturn(List.of(childField));
        when(childField.getFieldName()).thenReturn("child");
        when(childField.getAccounts()).thenReturn(null);
        when(childField.getChildFields()).thenReturn(List.of(grandChildField1, grandChildField2));
        when(grandChildField1.getFieldName()).thenReturn("grandChild1");
        when(grandChildField2.getFieldName()).thenReturn("grandChild2");
        when(grandChildField1.getAccounts()).thenReturn(null);
        when(grandChildField2.getAccounts()).thenReturn(null);
        when(grandChildField1.getChildFields()).thenReturn(null);
        when(grandChildField2.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();

        ReportTemplateFieldEntity child = entity.getFields().get(0).getChildFields().get(0);
        assertThat(child.getFieldOrder()).isZero();
        assertThat(child.getChildFields()).hasSize(2);
        assertThat(child.getChildFields().get(0).getFieldOrder()).isZero();
        assertThat(child.getChildFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toEntity_defaultFieldOrderIsZero() {
        ReportTemplateFieldEntity field = ReportTemplateFieldEntity.builder()
                .name("testField")
                .build();

        assertThat(field.getFieldOrder()).isZero();
    }

    @Test
    void toEntity_mergeFieldsInPlace_removesFieldsNotInDto() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1));
        when(field1.getFieldName()).thenReturn("field1");
        when(field1.getAccounts()).thenReturn(null);
        when(field1.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField2 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("field2")
                .fieldOrder(1)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1, existingField2)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getName()).isEqualTo("field1");
    }

    @Test
    void toEntity_mergeFieldsInPlace_preservesExistingFieldEntitiesWhenMatched() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(5)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getId()).isEqualTo(1L);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
    }

    @Test
    void toEntity_mergeFieldsInPlace_addsNewFieldsNotInExisting() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1Dto, field2Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field2Dto.getFieldName()).thenReturn("field2");
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field2Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);
        when(field2Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(2);
        assertThat(entity.getFields().get(1).getName()).isEqualTo("field2");
        assertThat(entity.getFields().get(1).getId()).isNull();
    }

    @Test
    void toEntity_mergeFieldsInPlace_handlesChildFieldsWhenUpdating() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childDto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentDto));
        when(parentDto.getFieldName()).thenReturn("parent");
        when(parentDto.getAccounts()).thenReturn(null);
        when(parentDto.getChildFields()).thenReturn(List.of(childDto));
        when(childDto.getFieldName()).thenReturn("child");
        when(childDto.getAccounts()).thenReturn(null);
        when(childDto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingParent = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parent")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingOldChild = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("oldChild")
                .fieldOrder(0)
                .parentField(existingParent)
                .build();

        existingParent.setChildFields(new ArrayList<>(List.of(existingOldChild)));
        existingTemplate.setFields(new ArrayList<>(List.of(existingParent)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields().get(0).getName()).isEqualTo("child");
    }

    @Test
    void toEntity_mergeFieldsInPlace_handlesNullChildFields() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentDto));
        when(parentDto.getFieldName()).thenReturn("parent");
        when(parentDto.getAccounts()).thenReturn(null);
        when(parentDto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingParent = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parent")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingChild = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("child")
                .fieldOrder(0)
                .parentField(existingParent)
                .build();

        existingParent.setChildFields(new ArrayList<>(List.of(existingChild)));
        existingTemplate.setFields(new ArrayList<>(List.of(existingParent)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).isEmpty();
    }

    @Test
    void toResponseDto_mapsFieldOrderInFieldDto() {
        ReportTemplateFieldEntity fieldEntity = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("testField")
                .fieldOrder(5)
                .build();

        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .fields(List.of(fieldEntity))
                .build();

        fieldEntity.setReportTemplate(template);

        ReportTemplateResponseDto dto = mapper.toResponseDto(template);

        assertThat(dto.getFields()).hasSize(1);
        assertThat(dto.getFields().get(0).getId()).isEqualTo(1L);
    }

    @Test
    void toResponseDto_nestedFieldsHaveCorrectStructure() {
        ReportTemplateFieldEntity childField = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("childField")
                .fieldOrder(0)
                .build();

        ReportTemplateFieldEntity parentField = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parentField")
                .fieldOrder(0)
                .childFields(List.of(childField))
                .build();

        childField.setParentField(parentField);

        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .fields(List.of(parentField))
                .build();

        parentField.setReportTemplate(template);

        ReportTemplateResponseDto dto = mapper.toResponseDto(template);

        assertThat(dto.getFields()).hasSize(1);
        assertThat(dto.getFields().get(0).getChildFields()).hasSize(1);
        assertThat(dto.getFields().get(0).getChildFields().get(0).getFieldName()).isEqualTo("childField");
    }

    @Test
    void toEntity_setsFieldOrderWhenCreatingFromDto() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field3Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1Dto, field2Dto, field3Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field2Dto.getFieldName()).thenReturn("field2");
        when(field3Dto.getFieldName()).thenReturn("field3");
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field2Dto.getAccounts()).thenReturn(null);
        when(field3Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);
        when(field2Dto.getChildFields()).thenReturn(null);
        when(field3Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(3);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
        assertThat(entity.getFields().get(2).getFieldOrder()).isEqualTo(2);
    }

    @Test
    void toResponseDto_handlesNullFieldList() {
        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .fields(null)
                .build();

        ReportTemplateResponseDto dto = mapper.toResponseDto(template);

        assertThat(dto.getFields()).isEmpty();
    }

    @Test
    void toEntity_complexMergeScenarioWithReordering() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field3Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field3Dto, field1Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field3Dto.getFieldName()).thenReturn("field3");
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field3Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);
        when(field3Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField2 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("field2")
                .fieldOrder(1)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField3 = ReportTemplateFieldEntity.builder()
                .id(3L)
                .name("field3")
                .fieldOrder(2)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1, existingField2, existingField3)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getName()).isEqualTo("field3");
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getName()).isEqualTo("field1");
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toEntity_usesExplicitOrderFromDtoWhenProvided() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field3 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1, field2, field3));
        when(field1.getFieldName()).thenReturn("field1");
        when(field2.getFieldName()).thenReturn("field2");
        when(field3.getFieldName()).thenReturn("field3");
        when(field1.getOrder()).thenReturn(5);
        when(field2.getOrder()).thenReturn(10);
        when(field3.getOrder()).thenReturn(15);
        when(field1.getAccounts()).thenReturn(null);
        when(field2.getAccounts()).thenReturn(null);
        when(field3.getAccounts()).thenReturn(null);
        when(field1.getChildFields()).thenReturn(null);
        when(field2.getChildFields()).thenReturn(null);
        when(field3.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(3);
        assertThat(entity.getFields().get(0).getFieldOrder()).isEqualTo(5);
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(10);
        assertThat(entity.getFields().get(2).getFieldOrder()).isEqualTo(15);
    }

    @Test
    void toEntity_usesLoopIndexWhenOrderIsZero() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field3 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1, field2, field3));
        when(field1.getFieldName()).thenReturn("field1");
        when(field2.getFieldName()).thenReturn("field2");
        when(field3.getFieldName()).thenReturn("field3");
        when(field1.getOrder()).thenReturn(0);
        when(field2.getOrder()).thenReturn(0);
        when(field3.getOrder()).thenReturn(0);
        when(field1.getAccounts()).thenReturn(null);
        when(field2.getAccounts()).thenReturn(null);
        when(field3.getAccounts()).thenReturn(null);
        when(field1.getChildFields()).thenReturn(null);
        when(field2.getChildFields()).thenReturn(null);
        when(field3.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(3);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
        assertThat(entity.getFields().get(2).getFieldOrder()).isEqualTo(2);
    }

    @Test
    void toEntity_mergeFieldsInPlace_usesExplicitOrderFromDtoWhenProvided() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1Dto, field2Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field2Dto.getFieldName()).thenReturn("field2");
        when(field1Dto.getOrder()).thenReturn(100);
        when(field2Dto.getOrder()).thenReturn(200);
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field2Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);
        when(field2Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField2 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("field2")
                .fieldOrder(1)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1, existingField2)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getFieldOrder()).isEqualTo(100);
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(200);
    }

    @Test
    void toEntity_mergeFieldsInPlace_usesLoopIndexWhenOrderIsZero() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto field1Dto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto field2Dto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(field1Dto, field2Dto));
        when(field1Dto.getFieldName()).thenReturn("field1");
        when(field2Dto.getFieldName()).thenReturn("field2");
        when(field1Dto.getOrder()).thenReturn(0);
        when(field2Dto.getOrder()).thenReturn(0);
        when(field1Dto.getAccounts()).thenReturn(null);
        when(field2Dto.getAccounts()).thenReturn(null);
        when(field1Dto.getChildFields()).thenReturn(null);
        when(field2Dto.getChildFields()).thenReturn(null);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingField1 = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("field1")
                .fieldOrder(5)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingField2 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("field2")
                .fieldOrder(10)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingField1, existingField2)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toEntity_mergeFieldsInPlace_setsParentFieldForNewChildFields() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto newChildDto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentDto));
        when(parentDto.getFieldName()).thenReturn("parent");
        when(parentDto.getAccounts()).thenReturn(null);
        when(parentDto.getChildFields()).thenReturn(List.of(newChildDto));
        when(parentDto.getOrder()).thenReturn(0);
        when(newChildDto.getFieldName()).thenReturn("newChild");
        when(newChildDto.getAccounts()).thenReturn(null);
        when(newChildDto.getChildFields()).thenReturn(null);
        when(newChildDto.getOrder()).thenReturn(0);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingParent = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parent")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        existingTemplate.setFields(new ArrayList<>(List.of(existingParent)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(1);
        ReportTemplateFieldEntity newChild = entity.getFields().get(0).getChildFields().get(0);
        assertThat(newChild.getName()).isEqualTo("newChild");
        assertThat(newChild.getParentField()).isNotNull();
        assertThat(newChild.getParentField().getName()).isEqualTo("parent");
    }

    @Test
    void toEntity_childFields_useExplicitOrderFromDtoWhenProvided() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField2 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentField));
        when(parentField.getFieldName()).thenReturn("parent");
        when(parentField.getAccounts()).thenReturn(null);
        when(parentField.getOrder()).thenReturn(0);
        when(parentField.getChildFields()).thenReturn(List.of(childField1, childField2));
        when(childField1.getFieldName()).thenReturn("child1");
        when(childField2.getFieldName()).thenReturn("child2");
        when(childField1.getOrder()).thenReturn(5);
        when(childField2.getOrder()).thenReturn(10);
        when(childField1.getAccounts()).thenReturn(null);
        when(childField2.getAccounts()).thenReturn(null);
        when(childField1.getChildFields()).thenReturn(null);
        when(childField2.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getChildFields().get(0).getFieldOrder()).isEqualTo(5);
        assertThat(entity.getFields().get(0).getChildFields().get(1).getFieldOrder()).isEqualTo(10);
    }

    @Test
    void toEntity_childFields_useLoopIndexWhenOrderIsZero() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentField = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField1 = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto childField2 = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentField));
        when(parentField.getFieldName()).thenReturn("parent");
        when(parentField.getAccounts()).thenReturn(null);
        when(parentField.getOrder()).thenReturn(0);
        when(parentField.getChildFields()).thenReturn(List.of(childField1, childField2));
        when(childField1.getFieldName()).thenReturn("child1");
        when(childField2.getFieldName()).thenReturn("child2");
        when(childField1.getOrder()).thenReturn(0);
        when(childField2.getOrder()).thenReturn(0);
        when(childField1.getAccounts()).thenReturn(null);
        when(childField2.getAccounts()).thenReturn(null);
        when(childField1.getChildFields()).thenReturn(null);
        when(childField2.getChildFields()).thenReturn(null);

        ReportTemplateEntity entity = mapper.toEntity(dto, null);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(2);
        assertThat(entity.getFields().get(0).getChildFields().get(0).getFieldOrder()).isZero();
        assertThat(entity.getFields().get(0).getChildFields().get(1).getFieldOrder()).isEqualTo(1);
    }

    @Test
    void toResponseDto_mapsOrderFieldFromEntity() {
        ReportTemplateFieldEntity fieldEntity = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("testField")
                .fieldOrder(5)
                .build();

        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .fields(List.of(fieldEntity))
                .build();

        fieldEntity.setReportTemplate(template);

        ReportTemplateResponseDto dto = mapper.toResponseDto(template);

        assertThat(dto.getFields()).hasSize(1);
        assertThat(dto.getFields().get(0).getOrder()).isEqualTo(5);
    }

    @Test
    void toResponseDto_mapsOrderFieldForNestedFields() {
        ReportTemplateFieldEntity childField1 = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("childField1")
                .fieldOrder(1)
                .build();

        ReportTemplateFieldEntity childField2 = ReportTemplateFieldEntity.builder()
                .id(3L)
                .name("childField2")
                .fieldOrder(2)
                .build();

        ReportTemplateFieldEntity parentField = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parentField")
                .fieldOrder(0)
                .childFields(List.of(childField1, childField2))
                .build();

        childField1.setParentField(parentField);
        childField2.setParentField(parentField);

        ReportTemplateEntity template = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("Test Template")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .fields(List.of(parentField))
                .build();

        parentField.setReportTemplate(template);

        ReportTemplateResponseDto dto = mapper.toResponseDto(template);

        assertThat(dto.getFields()).hasSize(1);
        assertThat(dto.getFields().get(0).getOrder()).isEqualTo(0);
        assertThat(dto.getFields().get(0).getChildFields()).hasSize(2);
        assertThat(dto.getFields().get(0).getChildFields().get(0).getOrder()).isEqualTo(1);
        assertThat(dto.getFields().get(0).getChildFields().get(1).getOrder()).isEqualTo(2);
    }

    @Test
    void toEntity_mergeFieldsInPlace_newChildFieldsHaveCorrectParent() {
        ReportTemplateDto dto = mock(ReportTemplateDto.class);
        ReportTemplateFieldDto parentDto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto existingChildDto = mock(ReportTemplateFieldDto.class);
        ReportTemplateFieldDto newChildDto = mock(ReportTemplateFieldDto.class);

        when(dto.getOrganisationId()).thenReturn("org-id");
        when(dto.getName()).thenReturn("name");
        when(dto.getDescription()).thenReturn("description");
        when(dto.getDataMode()).thenReturn("SYSTEM");
        when(dto.getReportTemplateType()).thenReturn("BALANCE_SHEET");
        when(dto.isActive()).thenReturn(true);
        when(dto.getFields()).thenReturn(List.of(parentDto));
        when(parentDto.getFieldName()).thenReturn("parent");
        when(parentDto.getAccounts()).thenReturn(null);
        when(parentDto.getOrder()).thenReturn(0);
        when(parentDto.getChildFields()).thenReturn(List.of(existingChildDto, newChildDto));
        when(existingChildDto.getFieldName()).thenReturn("existingChild");
        when(existingChildDto.getAccounts()).thenReturn(null);
        when(existingChildDto.getChildFields()).thenReturn(null);
        when(existingChildDto.getOrder()).thenReturn(0);
        when(newChildDto.getFieldName()).thenReturn("newChild");
        when(newChildDto.getAccounts()).thenReturn(null);
        when(newChildDto.getChildFields()).thenReturn(null);
        when(newChildDto.getOrder()).thenReturn(0);

        ReportTemplateEntity existingTemplate = ReportTemplateEntity.builder()
                .id("template-id")
                .organisationId("org-id")
                .name("name")
                .description("description")
                .reportTemplateType(ReportTemplateType.BALANCE_SHEET)
                .dataMode(DataMode.SYSTEM)
                .active(true)
                .build();

        ReportTemplateFieldEntity existingParent = ReportTemplateFieldEntity.builder()
                .id(1L)
                .name("parent")
                .fieldOrder(0)
                .reportTemplate(existingTemplate)
                .build();

        ReportTemplateFieldEntity existingChild = ReportTemplateFieldEntity.builder()
                .id(2L)
                .name("existingChild")
                .fieldOrder(0)
                .parentField(existingParent)
                .build();

        existingParent.setChildFields(new ArrayList<>(List.of(existingChild)));
        existingTemplate.setFields(new ArrayList<>(List.of(existingParent)));

        ReportTemplateEntity entity = mapper.toEntity(dto, existingTemplate);

        assertThat(entity.getFields()).hasSize(1);
        assertThat(entity.getFields().get(0).getChildFields()).hasSize(2);

        ReportTemplateFieldEntity updatedExistingChild = entity.getFields().get(0).getChildFields().get(0);
        assertThat(updatedExistingChild.getName()).isEqualTo("existingChild");
        assertThat(updatedExistingChild.getParentField()).isNotNull();
        assertThat(updatedExistingChild.getParentField().getName()).isEqualTo("parent");

        ReportTemplateFieldEntity newChild = entity.getFields().get(0).getChildFields().get(1);
        assertThat(newChild.getName()).isEqualTo("newChild");
        assertThat(newChild.getParentField()).isNotNull();
        assertThat(newChild.getParentField().getName()).isEqualTo("parent");
    }
}
