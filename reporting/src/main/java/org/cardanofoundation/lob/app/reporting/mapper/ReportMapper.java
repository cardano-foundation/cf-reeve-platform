package org.cardanofoundation.lob.app.reporting.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateFieldRepository;
import org.cardanofoundation.lob.app.reporting.viewConverter.ReportResponseConverter;

@Component
@RequiredArgsConstructor
public class ReportMapper {

    private final ReportTemplateFieldRepository reportTemplateFieldRepository;
    private final ReportResponseConverter reportResponseConverter;
    private final ReportTemplateMapper reportTemplateMapper;

    public ReportEntity toEntity(ReportDto dto, ReportEntity existingReport, ReportTemplateEntity template) {
        final ReportEntity report = existingReport != null ? existingReport : new ReportEntity();

        report.setReportTemplate(template);
        report.setOrganisationId(dto.getOrganisationId());
        report.setName(dto.getName());

        if (dto.getDataMode() != null) {
            report.setDataMode(DataMode.valueOf(dto.getDataMode()));
        }

        if (dto.getIntervalType() != null) {
            report.setIntervalType(IntervalType.valueOf(dto.getIntervalType()));
        }
        if (dto.getPeriod() != null) {
            report.setPeriod(dto.getPeriod());
        }
        if (dto.getYear() != null) {
            report.setYear(dto.getYear());
        }

        if (dto.getFields() != null) {
            List<ReportFieldEntity> newFields = dto.getFields().stream()
                .map(this::toColumnEntity)
                .toList();
            newFields.forEach(col -> setReportRecursively(col, report));

            // Update the existing collection instead of replacing it
            // This is required for cascade="all-delete-orphan" to work properly
            if (report.getFields() != null) {
                report.getFields().clear();
                report.getFields().addAll(newFields);
            } else {
                report.setFields(newFields);
            }
        }

        return report;
    }

    /**
     * Recursively sets the report reference for the field and all its children.
     * This ensures the report_id is properly populated in the database.
     */
    private void setReportRecursively(ReportFieldEntity field, ReportEntity report) {
        field.setReport(report);
        if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
            field.getChildFields().forEach(child -> setReportRecursively(child, report));
        }
    }

    public ReportResponseDto toResponseDto(ReportEntity entity) {
        if (entity == null) {
            return null;
        }

        List<ReportFieldDto> topLevelFields = entity.getFields() != null
            ? entity.getFields().stream()
                .filter(field -> field.getParentField() == null)
                .map(this::toColumnDto)
                .toList()
            : Collections.emptyList();

        ReportResponseDto responseDto = ReportResponseDto.builder()
                .id(entity.getId())
                .organisationId(entity.getOrganisationId())
                .reportTemplateId(entity.getReportTemplate() != null ? entity.getReportTemplate().getId() : null)
                .reportTemplateType(entity.getReportTemplate() != null ? entity.getReportTemplate().getReportTemplateType() : null)
                .name(entity.getName())
                .intervalType(entity.getIntervalType() != null ? entity.getIntervalType().name() : null)
                .period(entity.getPeriod())
                .year(entity.getYear())
                .ver(entity.getVer())
                .dataMode(entity.getDataMode() != null ? entity.getDataMode().name() : null)
                .isReadyToPublish(entity.isReadyToPublish())
                .isPublished(entity.isLedgerDispatchApproved())
                .blockchainTxId(entity.getBlockchainHash())
                .ledgerDispatchStatus(entity.getLedgerDispatchStatus())
                .publishError(entity.getPublishError() != null ? entity.getPublishError().name() : null)
                .isRejected(entity.isRejected())
                .rejectionReason(entity.getRejectionReason())
                .fields(topLevelFields)
                .failedValidationRules(Optional.ofNullable(entity.getFailedValidationRules()).orElse(List.of()).stream().map(reportTemplateMapper::toValidationRuleDto).toList())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .publishDate(entity.getLedgerDispatchDate())
                .publishedBy(entity.getPublishedBy())
                .error(Optional.empty())
                .build();
        responseDto.setFields(fillFieldsWithZero(responseDto.getFields(), entity.getReportTemplate().getFields()));
        return reportResponseConverter.convertResponse(responseDto, entity.getReportTemplate().getReportTemplateType());
    }

    private List<ReportFieldDto> fillFieldsWithZero(List<ReportFieldDto> fields, List<ReportTemplateFieldEntity> templateFields) {
        fields = new ArrayList<>(fields); // Avoid having an immutable list
        for(ReportTemplateFieldEntity fieldEntity : templateFields) {
            Optional<ReportFieldDto> exists = fields.stream().filter(f -> f.getTemplateFieldName().equals(fieldEntity.getName())).findFirst();
            if(exists.isEmpty()) {
                ReportFieldDto newField = ReportFieldDto.builder()
                        .templateFieldId(fieldEntity.getId())
                        .templateFieldName(fieldEntity.getName())
                        .value(BigDecimal.ZERO)
                        .childFields(fieldEntity.getChildFields() != null ?
                                fillFieldsWithZero(Collections.emptyList(), fieldEntity.getChildFields()) :
                                null)
                        .build();
                fields.add(newField);
            } else {
                ReportFieldDto existingField = exists.get();
                if(fieldEntity.getChildFields() != null && !fieldEntity.getChildFields().isEmpty()) {
                    existingField.setChildFields(
                            fillFieldsWithZero(
                                    existingField.getChildFields() != null ? existingField.getChildFields() : Collections.emptyList(),
                                    fieldEntity.getChildFields()
                            )
                    );
                }
            }
        }
        return fields;
    }

    public ReportFieldEntity toColumnEntity(ReportFieldDto dto) {
        ReportFieldEntity entity = ReportFieldEntity.builder()
                .value(dto.getValue())
                .build();

        // Link to template field if provided
        if (dto.getTemplateFieldId() != null) {
            ReportTemplateFieldEntity templateField = reportTemplateFieldRepository.findById(dto.getTemplateFieldId())
                    .orElse(null);
            entity.setFieldTemplate(templateField);
        }

        if (dto.getChildFields() != null) {
            List<ReportFieldEntity> childEntities = dto.getChildFields().stream()
                    .map(this::toColumnEntity)
                    .toList();
            childEntities.forEach(child -> child.setParentField(entity));
            entity.setChildFields(childEntities);
        }

        return entity;
    }

    private ReportFieldDto toColumnDto(ReportFieldEntity entity) {
        return ReportFieldDto.builder()
                .templateFieldId(entity.getFieldTemplate() != null ? entity.getFieldTemplate().getId() : null)
                .templateFieldName(entity.getFieldTemplate() != null ? entity.getFieldTemplate().getName() : null)
                .value(entity.getValue())
                .childFields(
                        entity.getChildFields() != null ?
                                entity.getChildFields().stream()
                                        .map(this::toColumnDto)
                                        .toList() :
                                null
                )
                .build();
    }
}
