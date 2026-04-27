package org.cardanofoundation.lob.app.reporting.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ValidationRuleTermEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ComparisonOperator;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.model.enums.TermOperation;
import org.cardanofoundation.lob.app.reporting.model.enums.TermSide;

@Component
@RequiredArgsConstructor
public class ReportTemplateMapper {

    private final ChartOfAccountRepository chartOfAccountRepository;

    public ReportTemplateEntity toEntity(ReportTemplateDto dto, ReportTemplateEntity existingTemplate) {
        final ReportTemplateEntity template = existingTemplate != null ? existingTemplate : new ReportTemplateEntity();

        template.setOrganisationId(dto.getOrganisationId());
        template.setName(dto.getName());
        template.setDescription(dto.getDescription());
        template.setReportTemplateType(ReportTemplateType.valueOf(dto.getReportTemplateType()));
        template.setActive(dto.isActive());
        template.setDataMode(DataMode.valueOf(dto.getDataMode()));

        if (dto.getFields() != null) {
            // If updating existing template, intelligently merge fields instead of replacing
            if (existingTemplate != null && template.getFields() != null) {
                mergeFieldsInPlace(template.getFields(), dto.getFields(), template);
            } else {
                // Creating new template or no existing fields - just map all new fields
                List<ReportTemplateFieldEntity> newColumns = dto.getFields().stream()
                    .map(columnDto -> toColumnEntity(columnDto, template, null))
                    .toList();
                template.setFields(newColumns);
            }
        }

        // Handle validation rules
        if (dto.getValidationRules() != null) {
            List<ReportTemplateValidationRuleEntity> newRules = dto.getValidationRules().stream()
                .map(ruleDto -> toValidationRuleEntity(ruleDto, template))
                .toList();

            if (template.getValidationRules() != null) {
                template.getValidationRules().clear();
                template.getValidationRules().addAll(newRules);
            } else {
                template.setValidationRules(newRules);
            }
        }

        return template;
    }

    /**
     * Intelligently merges DTO fields into existing entity fields in-place.
     * - Updates existing fields in-place (preserves entity relationships)
     * - Creates new fields that don't exist
     * - Removes fields that are no longer in the DTO
     */
    private void mergeFieldsInPlace(List<ReportTemplateFieldEntity> existingFields,
                                    List<ReportTemplateFieldDto> dtoFields,
                                    ReportTemplateEntity template) {
        if (dtoFields == null) {
            dtoFields = new ArrayList<>();
        }

        // Create a map of existing fields by name for quick lookup
        Map<String, ReportTemplateFieldEntity> existingFieldMap = existingFields.stream()
                .collect(Collectors.toMap(ReportTemplateFieldEntity::getName, f -> f));

        // Track which existing fields are still in the DTO
        Set<String> dtoFieldNames = dtoFields.stream()
                .map(ReportTemplateFieldDto::getFieldName)
                .collect(Collectors.toSet());

        // Remove fields that are no longer in the DTO
        existingFields.removeIf(field -> !dtoFieldNames.contains(field.getName()));

        // Update or create fields
        List<ReportTemplateFieldEntity> updatedFields = new ArrayList<>();
        for (ReportTemplateFieldDto dtoField : dtoFields) {
            ReportTemplateFieldEntity existingField = existingFieldMap.get(dtoField.getFieldName());

            if (existingField != null) {
                // Update existing field in-place
                updateFieldInPlace(existingField, dtoField, template);
                updatedFields.add(existingField);
            } else {
                // Create new field
                ReportTemplateFieldEntity newField = toColumnEntity(dtoField, template, null);
                updatedFields.add(newField);
            }
        }

        // Replace the list with updated fields (maintains order from DTO)
        existingFields.clear();
        existingFields.addAll(updatedFields);
    }

    /**
     * Updates an existing field entity with data from the DTO in-place.
     * Recursively handles child fields.
     */
    private void updateFieldInPlace(ReportTemplateFieldEntity existingField,
                                    ReportTemplateFieldDto dtoField,
                                    ReportTemplateEntity template) {
        existingField.setName(dtoField.getFieldName());
        existingField.setDateRange(Optional.ofNullable(dtoField.getDateRange()).orElse(ReportFieldDateRange.PERIOD));
        existingField.setNegated(dtoField.isNegated());

        // Update account mappings
        Set<ChartOfAccount> accounts = new HashSet<>();
        if (dtoField.getAccounts() != null && !dtoField.getAccounts().isEmpty()) {
            Set<ChartOfAccount.Id> ids = dtoField.getAccounts().stream()
                    .map(code -> new ChartOfAccount.Id(template.getOrganisationId(), code))
                    .collect(Collectors.toSet());
            accounts = new HashSet<>(chartOfAccountRepository.findAllById(ids));
        }
        existingField.setMappingAccounts(accounts);

        // Recursively merge child fields
        if (dtoField.getChildFields() != null && !dtoField.getChildFields().isEmpty()) {
            if (existingField.getChildFields() == null) {
                existingField.setChildFields(new ArrayList<>());
            }
            mergeFieldsInPlace(existingField.getChildFields(), dtoField.getChildFields(), template);
        } else {
            // Clear child fields if DTO has none
            if (existingField.getChildFields() != null) {
                existingField.getChildFields().clear();
            }
        }
    }


    public ReportTemplateResponseDto toResponseDto(ReportTemplateEntity entity) {
        if (entity == null) {
            return null;
        }

        List<ReportTemplateFieldDto> topLevelColumns = entity.getFields() != null
            ? entity.getFields().stream()
                .filter(col -> col.getParentField() == null)
                .map(this::toColumnDto)
                .toList()
            : Collections.emptyList();

        List<ValidationRuleDto> validationRules = entity.getValidationRules() != null
            ? entity.getValidationRules().stream()
                .map(this::toValidationRuleDto)
                .toList()
            : Collections.emptyList();

        return ReportTemplateResponseDto.builder()
            .id(entity.getId())
            .organisationId(entity.getOrganisationId())
            .name(entity.getName())
            .description(entity.getDescription())
            .reportTemplateType(entity.getReportTemplateType())
            .ver(entity.getVer())
            .active(entity.isActive())
            .editable(entity.isEditable())
            .fields(topLevelColumns)
            .validationRules(validationRules)
            .reportCount(entity.getReportCount())
            .dataMode(entity.getDataMode() != null ? entity.getDataMode().name() : null)
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .createdBy(entity.getCreatedBy())
            .updatedBy(entity.getUpdatedBy())
            .build();
    }

    private ReportTemplateFieldEntity toColumnEntity(
        ReportTemplateFieldDto dto,
        ReportTemplateEntity template,
        ReportTemplateFieldEntity parent
    ) {
        // Load mapping sub types if provided
        Set<ChartOfAccount> mappingAccounts = new HashSet<>();
        if (dto.getAccounts() != null && !dto.getAccounts().isEmpty()) {
            // Convert Long IDs to String and fetch
            Set<ChartOfAccount.Id> ids = dto.getAccounts().stream()
                .map(id -> new ChartOfAccount.Id(template.getOrganisationId(), id))
                .collect(Collectors.toSet());
            mappingAccounts = new HashSet<>(chartOfAccountRepository.findAllById(ids));
        }

        ReportTemplateFieldEntity column = ReportTemplateFieldEntity.builder()
            .reportTemplate(template)
            .parentField(parent)
            .name(dto.getFieldName())
            .dateRange(Optional.ofNullable(dto.getDateRange()).orElse(ReportFieldDateRange.PERIOD))
            .negated(dto.isNegated())
            .mappingAccounts(mappingAccounts)
            .build();

        if (dto.getChildFields() != null) {
            List<ReportTemplateFieldEntity> children = dto.getChildFields().stream()
                .map(childDto -> toColumnEntity(childDto, template, column))
                .toList();
            column.setChildFields(children);
        }

        return column;
    }

    private ReportTemplateFieldDto toColumnDto(ReportTemplateFieldEntity entity) {
        if (entity == null) {
            return null;
        }

        List<ReportTemplateFieldDto> children = entity.getChildFields() != null
            ? entity.getChildFields().stream()
                .map(this::toColumnDto)
                .toList()
            : Collections.emptyList();

        // Extract mapping sub type IDs
        Set<String> mappingAccountTypes = entity.getMappingAccounts() != null
            ? entity.getMappingAccounts().stream()
                .map(r -> r.getId().getCustomerCode()).collect(Collectors.toSet())
            : Collections.emptySet();

        return ReportTemplateFieldDto.builder()
            .id(entity.getId())
            .fieldName(entity.getName())
            .dateRange(entity.getDateRange())
            .negated(entity.isNegated())
            .accounts(mappingAccountTypes)
            .childFields(children)
            .build();
    }

    private ReportTemplateValidationRuleEntity toValidationRuleEntity(
        ValidationRuleDto dto,
        ReportTemplateEntity template
    ) {

        ReportTemplateValidationRuleEntity rule = ReportTemplateValidationRuleEntity.builder()
            .reportTemplate(template)
            .name(dto.getName())
            .operator(ComparisonOperator.valueOf(dto.getOperator()))
            .active(dto.isActive())
            .build();

        // Map left side terms
        List<ValidationRuleTermEntity> allTerms = new ArrayList<>();
        if (dto.getLeftSideTerms() != null) {
            for (int i = 0; i < dto.getLeftSideTerms().size(); i++) {
                ValidationRuleTermDto termDto = dto.getLeftSideTerms().get(i);
                allTerms.add(toValidationRuleTermEntity(termDto, rule, TermSide.LEFT, i));
            }
        }

        // Map right side terms
        if (dto.getRightSideTerms() != null) {
            for (int i = 0; i < dto.getRightSideTerms().size(); i++) {
                ValidationRuleTermDto termDto = dto.getRightSideTerms().get(i);
                allTerms.add(toValidationRuleTermEntity(termDto, rule, TermSide.RIGHT, i));
            }
        }

        rule.setTerms(allTerms);
        return rule;
    }

    private ValidationRuleTermEntity toValidationRuleTermEntity(
        ValidationRuleTermDto dto,
        ReportTemplateValidationRuleEntity rule,
        TermSide side,
        int order
    ) {
        // Find the field entity by concatenated name (e.g., "parent.child.grandchild")
        ReportTemplateFieldEntity field = findFieldByConcatenatedName(rule.getReportTemplate(), dto.getFieldName());

        return ValidationRuleTermEntity.builder()
            .validationRule(rule)
            .field(field)
            .operation(TermOperation.valueOf(dto.getOperation()))
            .side(side)
            .termOrder(order)
            .build();
    }

    private ReportTemplateFieldEntity findFieldByConcatenatedName(ReportTemplateEntity template, String concatenatedName) {
        String[] names = concatenatedName.split("\\.");
        List<ReportTemplateFieldEntity> fields = template.getFields();
        ReportTemplateFieldEntity currentField = null;
        for (String name : names) {
            currentField = null;
            for (ReportTemplateFieldEntity field : fields) {
                if (field.getName().equals(name)) {
                    currentField = field;
                    fields = field.getChildFields();
                    break;
                }
            }
            if (currentField == null) {
                return null; // Field not found
            }
        }
        return currentField;
    }

    private ReportTemplateFieldEntity findFieldByName(ReportTemplateEntity template, String fieldName) {
        if (template.getFields() == null || fieldName == null) {
            return null;
        }

        for (ReportTemplateFieldEntity field : template.getFields()) {
            ReportTemplateFieldEntity found = findFieldByNameRecursive(field, fieldName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ReportTemplateFieldEntity findFieldByNameRecursive(ReportTemplateFieldEntity field, String fieldName) {
        if (field.getName() != null && field.getName().equals(fieldName)) {
            return field;
        }

        if (field.getChildFields() != null) {
            for (ReportTemplateFieldEntity child : field.getChildFields()) {
                ReportTemplateFieldEntity found = findFieldByNameRecursive(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    public ValidationRuleDto toValidationRuleDto(ReportTemplateValidationRuleEntity entity) {
        if (entity == null) {
            return null;
        }

        List<ValidationRuleTermDto> leftTerms = entity.getTerms() != null
            ? entity.getTerms().stream()
                .filter(term -> term.getSide() == TermSide.LEFT)
                .sorted((a, b) -> Integer.compare(a.getTermOrder(), b.getTermOrder()))
                .map(this::toValidationRuleTermDto)
                .toList()
            : Collections.emptyList();

        List<ValidationRuleTermDto> rightTerms = entity.getTerms() != null
            ? entity.getTerms().stream()
                .filter(term -> term.getSide() == TermSide.RIGHT)
                .sorted((a, b) -> Integer.compare(a.getTermOrder(), b.getTermOrder()))
                .map(this::toValidationRuleTermDto)
                .toList()
            : Collections.emptyList();

        return ValidationRuleDto.builder()
            .name(entity.getName())
            .operator(entity.getOperator().name())
            .active(entity.isActive())
            .leftSideTerms(leftTerms)
            .rightSideTerms(rightTerms)
            .build();
    }

    private ValidationRuleTermDto toValidationRuleTermDto(ValidationRuleTermEntity entity) {
        if (entity == null) {
            return null;
        }

        return ValidationRuleTermDto.builder()
            .fieldName(entity.getField() != null ? entity.getField().getName() : null)
            .operation(entity.getOperation().name())
            .build();
    }
}
