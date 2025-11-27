package org.cardanofoundation.lob.app.reporting.service;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTemplateTypeValidator;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    private final ReportingRepository reportingRepository;
    private final Validator validator;
    private final ReportTemplateTypeValidator reportTemplateTypeValidator;

    public Either<Problem, ReportTemplateResponseDto> create(ReportTemplateDto dto) {
        log.info("Creating report template: {}", dto.getName());
        Either<Problem, Void> errorDetails = validateReportTemplateDto(dto);
        if (errorDetails.isLeft()) return Either.left(errorDetails.getLeft());
        // Validate no duplicate field names under same parent
        Either<Problem, Void> duplicateValidation = validateNoDuplicateFieldNames(dto.getFields());
        if (duplicateValidation.isLeft()) {
            return Either.left(duplicateValidation.getLeft());
        }

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        // Check if a template with the same name already exists for this organisation
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndName(dto.getOrganisationId(), dto.getName());

        if (existingTemplateOpt.isPresent()) {
            return Either.left(Problem.builder()
                    .withTitle("Template Already Exists")
                    .withDetail("A template with name '" + dto.getName() + "' already exists for this organisation. Use PUT to update.")
                    .withStatus(Status.CONFLICT)
                    .build());
        }
        try {
            ReportTemplateType.valueOf(dto.getReportTemplateType());
        } catch (IllegalArgumentException e) {
            return Either.left(Problem.builder()
                    .withTitle("Invalid Report Template Type")
                    .withDetail("The report template type '" + dto.getReportTemplateType() + "' is not valid.")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // New template - create with version 1
        log.info("Creating new template: {}", dto.getName());
        ReportTemplateEntity templateToSave = reportTemplateMapper.toEntity(dto, null);

        Either<Problem, Void> reportTypeValidated = reportTemplateTypeValidator.validateReportTemplateType(templateToSave);
        if(reportTypeValidated.isLeft()) {
            return Either.left(reportTypeValidated.getLeft());
        }
        ReportTemplateEntity saved = reportTemplateRepository.save(templateToSave);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    private List<ObjectError> getValidationErrorsOfFields(List<ReportTemplateFieldDto> fields) {
        List<ObjectError> allErrors = new ArrayList<>();
        if (fields != null) {
            for (ReportTemplateFieldDto field : fields) {
                Errors fieldErrors = validator.validateObject(field);
                allErrors.addAll(fieldErrors.getAllErrors());
                allErrors.addAll(getValidationErrorsOfFields(field.getChildFields()));
            }
        }
        return allErrors;
    }

    private Either<Problem, Void> validateReportTemplateDto(ReportTemplateDto dto) {
        Errors errors = validator.validateObject(dto);
        List<ObjectError> allErrors = new ArrayList<>(errors.getAllErrors());
        allErrors.addAll(getValidationErrorsOfFields(dto.getFields()));
        if (!allErrors.isEmpty()) {
            String errorDetails = allErrors.stream()
                    .map(ObjectError::getDefaultMessage)
                    .collect(Collectors.joining(", "));
            return Either.left(Problem.builder()
                    .withTitle("Validation Error")
                    .withDetail(errorDetails)
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        return Either.right(null);
    }

    public Either<Problem, ReportTemplateResponseDto> update(ReportTemplateDto dto) {
        log.info("Updating report template: {}", dto.getName());
        Either<Problem, Void> errorDetails = validateReportTemplateDto(dto);
        if (errorDetails.isLeft()) return Either.left(errorDetails.getLeft());
        // Validate no duplicate field names under same parent
        Either<Problem, Void> duplicateValidation = validateNoDuplicateFieldNames(dto.getFields());
        if (duplicateValidation.isLeft()) {
            return Either.left(duplicateValidation.getLeft());
        }

        // Check if a template with the same name exists for this organisation
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndName(dto.getOrganisationId(), dto.getName());

        if (existingTemplateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Template Not Found")
                    .withDetail("No template with name '" + dto.getName() + "' exists for this organisation. Use POST to create.")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        ReportTemplateEntity existing = existingTemplateOpt.get();
        ReportTemplateEntity templateToSave;

        // Check if there are any reports using this template
        List<org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(existing.getId());
        try {
            ReportTemplateType.valueOf(dto.getReportTemplateType());
        } catch (IllegalArgumentException e) {
            return Either.left(Problem.builder()
                    .withTitle("Invalid Report Template Type")
                    .withDetail("The report template type '" + dto.getReportTemplateType() + "' is not valid.")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        if (!existingReports.isEmpty()) {
            // Reports exist - create a new version
            log.info("Template '{}' has {} existing reports, creating new version {} -> {}",
                    dto.getName(), existingReports.size(), existing.getVer(), existing.getVer() + 1);

            templateToSave = reportTemplateMapper.toEntity(dto, null);
            templateToSave.setVer(existing.getVer() + 1);
        } else {
            // No reports exist - update existing template in place
            log.info("Template '{}' has no existing reports, updating in place", dto.getName());
            templateToSave = reportTemplateMapper.toEntity(dto, existing);
        }

        Either<Problem, Void> reportTypeValidated = reportTemplateTypeValidator.validateReportTemplateType(templateToSave);
        if(reportTypeValidated.isLeft()) {
            return Either.left(reportTypeValidated.getLeft());
        }
        ReportTemplateEntity saved = reportTemplateRepository.save(templateToSave);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    @Transactional(readOnly = true)
    public Optional<ReportTemplateResponseDto> findById(String id) {
        return reportTemplateRepository.findById(id)
                .map(reportTemplateMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public List<ReportTemplateResponseDto> findByOrganisationId(String organisationId) {
        return reportTemplateRepository.findByOrganisationId(organisationId).stream()
                .map(reportTemplateMapper::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportTemplateResponseDto> findAll() {
        return reportTemplateRepository.findAll().stream()
                .map(reportTemplateMapper::toResponseDto)
                .toList();
    }

    public Either<Problem, Void> delete(String id) {
        log.info("Deleting report template id: {}", id);

        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(id);
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("TEMPLATE_NOT_FOUND")
                    .withDetail("Report template with ID " + id + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        // Check if there are any reports using this template
        List<org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(id);

        if (!existingReports.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("TEMPLATE_IN_USE")
                    .withDetail("Cannot delete template with ID " + id + " because it has " +
                            existingReports.size() + " associated report(s)")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        reportTemplateRepository.deleteById(id);
        return Either.right(null);
    }

    @Transactional(readOnly = true)
    public boolean existsByOrganisationIdAndName(String organisationId, String name) {
        return reportTemplateRepository.existsByOrganisationIdAndName(organisationId, name);
    }

    private Either<Problem, Void> validateNoDuplicateFieldNames(List<ReportTemplateFieldDto> fields) {
        return validateNoDuplicateFieldNamesRecursive(fields, null);
    }

    private Either<Problem, Void> validateNoDuplicateFieldNamesRecursive(List<ReportTemplateFieldDto> fields, String parentName) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Check for duplicates at this level
        Set<String> fieldNames = new HashSet<>();
        for (ReportTemplateFieldDto field : fields) {
            if (fieldNames.contains(field.getFieldName())) {
                String parentInfo = parentName != null ? " under parent '" + parentName + "'" : " at root level";
                return Either.left(Problem.builder()
                        .withTitle("Duplicate Field Name")
                        .withDetail("Duplicate field name '" + field.getFieldName() + "'" + parentInfo + ". Field names must be unique within the same parent.")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            fieldNames.add(field.getFieldName());

            // Recursively validate child fields
            if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
                Either<Problem, Void> childValidation = validateNoDuplicateFieldNamesRecursive(
                        field.getChildFields(),
                        field.getFieldName()
                );
                if (childValidation.isLeft()) {
                    return childValidation;
                }
            }
        }

        return Either.right(null);
    }

    private Either<Problem, Void> validateSubTypes(List<ReportTemplateFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Collect all subtype IDs from all fields (including nested)
        Set<Long> allSubTypeIds = new HashSet<>();
        collectSubTypeIds(fields, allSubTypeIds);

        if (allSubTypeIds.isEmpty()) {
            return Either.right(null);
        }

        // Convert Long IDs to String for repository lookup
        List<String> stringIds = allSubTypeIds.stream()
                .map(String::valueOf)
                .collect(toList());

        // Fetch existing subtypes
        List<String> existingSubTypeIds = chartOfAccountSubTypeRepository.findAllById(stringIds).stream()
                .map(subType -> String.valueOf(subType.getId()))
                .collect(toList());

        // Find missing subtypes
        Set<String> missingSubTypeIds = stringIds.stream()
                .filter(id -> !existingSubTypeIds.contains(id))
                .collect(Collectors.toSet());

        if (!missingSubTypeIds.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Invalid SubType IDs")
                    .withDetail("The following subtype IDs do not exist: " + missingSubTypeIds)
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        return Either.right(null);
    }

    private void collectSubTypeIds(List<ReportTemplateFieldDto> fields, Set<Long> subTypeIds) {
        if (fields == null) {
            return;
        }

        for (ReportTemplateFieldDto field : fields) {
            if (field.getMappingSubTypeIds() != null) {
                subTypeIds.addAll(field.getMappingSubTypeIds());
            }
            if (field.getChildFields() != null) {
                collectSubTypeIds(field.getChildFields(), subTypeIds);
            }
        }
    }

}
