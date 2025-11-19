package org.cardanofoundation.lob.app.reporting.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;

@Component
@RequiredArgsConstructor
public class ReportTemplateMapper {

    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;

    public ReportTemplateEntity toEntity(ReportTemplateDto dto, ReportTemplateEntity existingTemplate) {
        final ReportTemplateEntity template = existingTemplate != null ? existingTemplate : new ReportTemplateEntity();

        template.setOrganisationId(dto.getOrganisationId());
        template.setName(dto.getName());
        template.setDescription(dto.getDescription());
        template.setCurrencyId(dto.getCurrencyId());

        if (dto.getFields() != null) {
            List<ReportTemplateFieldEntity> newColumns = dto.getFields().stream()
                .map(columnDto -> toColumnEntity(columnDto, template, null))
                .collect(Collectors.toList());

            // Update the existing collection instead of replacing it
            // This is required for cascade="all-delete-orphan" to work properly
            if (template.getColumns() != null) {
                template.getColumns().clear();
                template.getColumns().addAll(newColumns);
            } else {
                template.setColumns(newColumns);
            }
        }

        return template;
    }

    public ReportTemplateResponseDto toResponseDto(ReportTemplateEntity entity) {
        if (entity == null) {
            return null;
        }

        List<ReportTemplateFieldDto> topLevelColumns = entity.getColumns() != null
            ? entity.getColumns().stream()
                .filter(col -> col.getParentField() == null)
                .map(this::toColumnDto)
                .collect(Collectors.toList())
            : Collections.emptyList();

        return ReportTemplateResponseDto.builder()
            .id(entity.getId())
            .organisationId(entity.getOrganisationId())
            .name(entity.getName())
            .description(entity.getDescription())
            .currencyId(entity.getCurrencyId())
            .ver(entity.getVer())
            .columns(topLevelColumns)
            .build();
    }

    private ReportTemplateFieldEntity toColumnEntity(
        ReportTemplateFieldDto dto,
        ReportTemplateEntity template,
        ReportTemplateFieldEntity parent
    ) {
        // Load mapping sub types if provided
        List<ChartOfAccountSubType> mappingTypes = new ArrayList<>();
        if (dto.getMappingSubTypeIds() != null && !dto.getMappingSubTypeIds().isEmpty()) {
            // Convert Long IDs to String and fetch
            List<String> stringIds = dto.getMappingSubTypeIds().stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
            mappingTypes = chartOfAccountSubTypeRepository.findAllById(stringIds);
        }

        ReportTemplateFieldEntity column = ReportTemplateFieldEntity.builder()
            .reportTemplate(template)
            .parentField(parent)
            .name(dto.getFieldName())
            .accumulated(dto.isAccumulated())
            .accumulatedYearly(dto.isAccumulatedYearly())
            .accumulatedPreviousYear(dto.isAccumulatedPreviousYear())
            .negated(dto.isNegated())
            .mappingTypes(mappingTypes)
            .build();

        if (dto.getChildFields() != null) {
            List<ReportTemplateFieldEntity> children = dto.getChildFields().stream()
                .map(childDto -> toColumnEntity(childDto, template, column))
                .collect(Collectors.toList());
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
                .collect(Collectors.toList())
            : Collections.emptyList();

        // Extract mapping sub type IDs
        List<Long> mappingSubTypeIds = entity.getMappingTypes() != null
            ? entity.getMappingTypes().stream()
                .map(ChartOfAccountSubType::getId)
                .collect(Collectors.toList())
            : Collections.emptyList();

        return ReportTemplateFieldDto.builder()
            .id(entity.getId())
            .fieldName(entity.getName())
            .accumulated(entity.isAccumulated())
            .accumulatedYearly(entity.isAccumulatedYearly())
            .accumulatedPreviousYear(entity.isAccumulatedPreviousYear())
            .negated(entity.isNegated())
            .mappingSubTypeIds(mappingSubTypeIds)
            .childFields(children)
            .build();
    }
}
