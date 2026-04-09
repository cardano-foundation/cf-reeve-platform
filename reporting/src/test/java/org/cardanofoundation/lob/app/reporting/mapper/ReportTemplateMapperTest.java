package org.cardanofoundation.lob.app.reporting.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
}
