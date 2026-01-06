package org.cardanofoundation.lob.app.reporting.service;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleDto;
import org.cardanofoundation.lob.app.reporting.dto.ValidationRuleTermDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ComparisonOperator;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.model.enums.TermOperation;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTemplateTypeValidator;
import org.cardanofoundation.lob.app.reporting.util.Constants;
import org.cardanofoundation.lob.app.reporting.util.Helper;

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

    private Either<Problem, Void> validateReport(ReportTemplateDto dto) {
        Either<Problem, Void> errorDetails = validateReportTemplateDto(dto);
        if (errorDetails.isLeft()) return Either.left(errorDetails.getLeft());

        Either<Problem, Void> validDataMode = validateDataMode(dto);
        if (validDataMode.isLeft()) return Either.left(validDataMode.getLeft());

        // Validate no duplicate field names under same parent
        Either<Problem, Void> duplicateValidation = validateNoDuplicateFields(dto.getFields());
        if (duplicateValidation.isLeft()) {
            return Either.left(duplicateValidation.getLeft());
        }

        Either<Problem, Void> forbiddenCharacters = validateForbiddenCharacters(dto.getFields());
        if (forbiddenCharacters.isLeft()) {
            return Either.left(forbiddenCharacters.getLeft());
        }

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        // Validate validation rules
        Either<Problem, Void> validationRulesValidation = validateValidationRules(dto);
        if (validationRulesValidation.isLeft()) {
            return Either.left(validationRulesValidation.getLeft());
        }

        return Either.right(null);
    }

    private Either<Problem, Void> validateForbiddenCharacters(List<ReportTemplateFieldDto> fields) {
        for(ReportTemplateFieldDto field : fields) {
            if(!field.getChildFields().isEmpty()) {
                Either<Problem, Void> childValidation = validateForbiddenCharacters(field.getChildFields());
                if (childValidation.isLeft()) {
                    return childValidation;
                }
            }
            if(Helper.containsForbiddenCharacters(field.getFieldName())) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_FIELD_NAME)
                        .withDetail("Field name '" + field.getFieldName() + "' contains forbidden characters. Only alphanumeric characters are allowed. Following Characters aren't allowed: " + Helper.FORBIDDEN_CHARACTERS)
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
        }
        return Either.right(null);
    }

    public Either<Problem, ReportTemplateResponseDto> create(ReportTemplateDto dto) {
        log.info("Creating report template: {}", dto.getName());
        Either<Problem, Void> validateReport = validateReport(dto);
        if (validateReport.isLeft()) return Either.left(validateReport.getLeft());
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

    private Either<Problem, Void> validateDataMode(ReportTemplateDto dto) {
        DataMode dataMode;
        try {
            dataMode = DataMode.valueOf(dto.getDataMode());
        } catch (IllegalArgumentException e) {
            return Either.left(Helper.buildDataModeError(dto.getDataMode()));
        }
        if(dataMode.equals(DataMode.SYSTEM) && !hasAllMappings(dto.getFields())) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.INVALID_FIELD_MAPPINGS)
                    .withDetail("All fields must have mappings when data mode is SYSTEM")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        if(dataMode.equals(DataMode.USER) && hasMappings(dto.getFields())) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.INVALID_FIELD_MAPPINGS)
                    .withDetail("No field mappings are allowed in a USER data mode template")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        return Either.right(null);
    }

    private boolean hasMappings(List<ReportTemplateFieldDto> fields) {
        boolean hasMappings = false;
        for (ReportTemplateFieldDto field : fields) {
            if(!field.getMappingSubTypeIds().isEmpty()) {
                hasMappings = true;
                break;
            }
            if(!field.getChildFields().isEmpty()) {
                hasMappings = hasMappings(field.getChildFields());
                if(hasMappings) {
                    break;
                }
            }
        }
        return hasMappings;
    }

    private boolean hasAllMappings(List<ReportTemplateFieldDto> fields) {
        boolean hasAllMappings = true;
        for (ReportTemplateFieldDto field : fields) {
            if(field.getMappingSubTypeIds().isEmpty() && field.getChildFields().isEmpty()) {
                hasAllMappings = false;
                break;
            }
            if(!field.getChildFields().isEmpty()) {
                hasAllMappings = hasAllMappings(field.getChildFields());
                if(!hasAllMappings) {
                    break;
                }
            }
        }
        return hasAllMappings;
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
        Either<Problem, Void> validateReport = validateReport(dto);
        if (validateReport.isLeft()) return Either.left(validateReport.getLeft());

        // Check if a template with the same name exists for this organisation
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndId(dto.getOrganisationId(), dto.getId());

        if (existingTemplateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Template Not Found")
                    .withDetail("No template with id '" + dto.getId() + "' exists for this organisation. Use POST to create.")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportTemplateEntity existing = existingTemplateOpt.get();
        ReportTemplateEntity templateToSave;

        // Check if there are any reports using this template
        List<ReportEntity> existingReports =
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
        List<ReportEntity> existingReports =
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

    private Either<Problem, Void> validateNoDuplicateFields(List<ReportTemplateFieldDto> fields) {
        Either<Problem, Void> duplicateFieldsNamesRecursive = validateNoDuplicateFieldsNamesRecursive(fields, null);
        if (duplicateFieldsNamesRecursive.isLeft()) {
            return duplicateFieldsNamesRecursive;
        }
        return Either.right(null);
    }

    private Either<Problem, Void> validateNoDuplicateFieldsNamesRecursive(List<ReportTemplateFieldDto> fields, String parentName) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Check for duplicates at this level
        Set<String> fieldNames = new HashSet<>();
        for (ReportTemplateFieldDto field : fields) {
            if (fieldNames.contains(field.getFieldName())) {
                String parentInfo = parentName != null ? " under parent '" + parentName + "'" : " at root level";
                return Either.left(Problem.builder()
                        .withTitle("DUPLICATE_FIELD_NAME")
                        .withDetail("Duplicate field name '" + field.getFieldName() + "'" + parentInfo + ". Field names must be unique within the same parent.")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            fieldNames.add(field.getFieldName());

            // Recursively validate child fields
            if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
                Either<Problem, Void> childValidation = validateNoDuplicateFieldsNamesRecursive(
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
                .toList();

        // Fetch existing subtypes
        List<String> existingSubTypeIds = chartOfAccountSubTypeRepository.findAllById(stringIds).stream()
                .map(subType -> String.valueOf(subType.getId()))
                .toList();

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

    private Either<Problem, Void> validateValidationRules(ReportTemplateDto dto) {
        if (dto.getValidationRules() == null || dto.getValidationRules().isEmpty()) {
            return Either.right(null);
        }

        // Collect all field names from the template
        Set<String> allFieldNames = new HashSet<>();
        collectFieldNames(dto.getFields(), allFieldNames);

        for (ValidationRuleDto rule : dto.getValidationRules()) {
            // Validate rule has a name
            if (rule.getName() == null || rule.getName().trim().isEmpty()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_VALIDATION_RULE)
                        .withDetail("Validation rule must have a name")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Validate operator
            try {
                ComparisonOperator.valueOf(rule.getOperator());
            } catch (IllegalArgumentException e) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_VALIDATION_RULE)
                        .withDetail("Invalid comparison operator: " + rule.getOperator() + ". Must be one of: GREATER_THAN_OR_EQUAL, EQUAL, LESS_THAN_OR_EQUAL")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Validate left side terms
            if (rule.getLeftSideTerms() == null || rule.getLeftSideTerms().isEmpty()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_VALIDATION_RULE)
                        .withDetail(Constants.VALIDATION_RULE_S_MUST_HAVE_AT_LEAST_ONE_TERM_ON_THE_LEFT_SIDE.formatted(rule.getName()))
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Validate right side terms
            if (rule.getRightSideTerms() == null || rule.getRightSideTerms().isEmpty()) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_VALIDATION_RULE)
                        .withDetail(Constants.VALIDATION_RULE_S_MUST_HAVE_AT_LEAST_ONE_TERM_ON_THE_LEFT_SIDE.formatted(rule.getName()))
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Validate all terms reference existing fields
            List<ValidationRuleTermDto> allTerms = new ArrayList<>();
            allTerms.addAll(rule.getLeftSideTerms());
            allTerms.addAll(rule.getRightSideTerms());

            for (ValidationRuleTermDto term : allTerms) {
                if (term.getFieldName() == null || term.getFieldName().trim().isEmpty()) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.INVALID_VALIDATION_RULE)
                            .withDetail(Constants.VALIDATION_RULE_S_MUST_HAVE_AT_LEAST_ONE_TERM_ON_THE_LEFT_SIDE.formatted(rule.getName()))
                            .withStatus(Status.BAD_REQUEST)
                            .build());
                }
                List<String> namesNotExist = Arrays.stream(term.getFieldName().split("\\.")).filter(s -> !allFieldNames.contains(s)).toList();
                if (!namesNotExist.isEmpty()) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.INVALID_VALIDATION_RULE)
                            .withDetail("Validation rule '" + rule.getName() + "' references field names '" + namesNotExist + "' which do not exist in the template")
                            .withStatus(Status.BAD_REQUEST)
                            .build());
                }

                // Validate operation
                try {
                    TermOperation.valueOf(term.getOperation());
                } catch (IllegalArgumentException e) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.INVALID_VALIDATION_RULE)
                            .withDetail("Invalid term operation: " + term.getOperation() + ". Must be one of: ADD, SUBTRACT")
                            .withStatus(Status.BAD_REQUEST)
                            .build());
                }
            }
        }

        return Either.right(null);
    }

    private void collectFieldNames(List<ReportTemplateFieldDto> fields, Set<String> fieldNames) {
        if (fields == null) {
            return;
        }

        for (ReportTemplateFieldDto field : fields) {
            if (field.getFieldName() != null) {
                fieldNames.add(field.getFieldName());
            }
            if (field.getChildFields() != null) {
                collectFieldNames(field.getChildFields(), fieldNames);
            }
        }
    }

}
