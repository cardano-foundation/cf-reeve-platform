package org.cardanofoundation.lob.app.reporting.mapper;

import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
