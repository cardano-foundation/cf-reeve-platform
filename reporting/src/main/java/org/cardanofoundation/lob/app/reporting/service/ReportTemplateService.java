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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateListResponseDto;
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
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final ReportingRepository reportingRepository;
    private final Validator validator;
    private final ReportTemplateTypeValidator reportTemplateTypeValidator;

    private Either<ProblemDetail, Void> validateReport(ReportTemplateDto dto) {
        Either<ProblemDetail, Void> errorDetails = validateReportTemplateDto(dto);
        if (errorDetails.isLeft()) return Either.left(errorDetails.getLeft());

        Either<ProblemDetail, Void> validDataMode = validateDataMode(dto);
        if (validDataMode.isLeft()) return Either.left(validDataMode.getLeft());

        // Validate no duplicate field names under same parent
        Either<ProblemDetail, Void> duplicateValidation = validateNoDuplicateFields(dto.getFields());
        if (duplicateValidation.isLeft()) {
            return Either.left(duplicateValidation.getLeft());
        }

        Either<ProblemDetail, Void> forbiddenCharacters = validateForbiddenCharacters(dto.getFields());
        if (forbiddenCharacters.isLeft()) {
            return Either.left(forbiddenCharacters.getLeft());
        }

        // Validate subtypes exist
        Either<ProblemDetail, Void> subtypeValidation = validateAccounts(dto.getFields(), dto.getOrganisationId());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        // Validate validation rules
        Either<ProblemDetail, Void> validationRulesValidation = validateValidationRules(dto);
        if (validationRulesValidation.isLeft()) {
            return Either.left(validationRulesValidation.getLeft());
        }

        return Either.right(null);
    }

    private boolean hasDuplicate(ReportTemplateFieldDto field, Set<String> seenAccountMappings) {
        if(field.getAccounts().stream().anyMatch(s -> !seenAccountMappings.add(s))) {
            return true;
        }
        return field.getChildFields().stream().anyMatch(f -> hasDuplicate(f, seenAccountMappings));
    }

    private Either<ProblemDetail, Void> validateForbiddenCharacters(List<ReportTemplateFieldDto> fields) {
        for(ReportTemplateFieldDto field : fields) {
            if(!field.getChildFields().isEmpty()) {
                Either<ProblemDetail, Void> childValidation = validateForbiddenCharacters(field.getChildFields());
                if (childValidation.isLeft()) {
                    return childValidation;
                }
            }
            if(Helper.containsForbiddenCharacters(field.getFieldName())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Field name '" + field.getFieldName() + "' contains forbidden characters. Only alphanumeric characters are allowed. Following Characters aren't allowed: " + Helper.FORBIDDEN_CHARACTERS);
                problem.setTitle(Constants.INVALID_FIELD_NAME);
                return Either.left(problem);
            }
        }
        return Either.right(null);
    }

    public Either<ProblemDetail, ReportTemplateResponseDto> create(ReportTemplateDto dto) {
        log.info("Creating report template: {}", dto.getName());
        Either<ProblemDetail, Void> validateReport = validateReport(dto);
        if (validateReport.isLeft()) return Either.left(validateReport.getLeft());
        // Check if a template with the same name already exists for this organisation
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndName(dto.getOrganisationId(), dto.getName());

        if (existingTemplateOpt.isPresent()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A template with name '" + dto.getName() + "' already exists for this organisation.");
            problem.setTitle("Template Already Exists");
            return Either.left(problem);
        }
        try {
            ReportTemplateType.valueOf(dto.getReportTemplateType());
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The report template type '" + dto.getReportTemplateType() + "' is not valid.");
            problem.setTitle("Invalid Report Template Type");
            return Either.left(problem);
        }

        // New template - create with version 1
        log.info("Creating new template: {}", dto.getName());
        ReportTemplateEntity templateToSave = reportTemplateMapper.toEntity(dto, null);
        Either<ProblemDetail, Void> reportTypeValidated = reportTemplateTypeValidator.validateReportTemplateType(templateToSave);
        if(reportTypeValidated.isLeft()) {
            return Either.left(reportTypeValidated.getLeft());
        }
        ReportTemplateEntity saved = reportTemplateRepository.save(templateToSave);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    private Either<ProblemDetail, Void> validateDataMode(ReportTemplateDto dto) {
        DataMode dataMode;
        try {
            dataMode = DataMode.valueOf(dto.getDataMode());
        } catch (IllegalArgumentException e) {
            return Either.left(Helper.buildDataModeError(dto.getDataMode()));
        }
        if(dataMode.equals(DataMode.SYSTEM) && !hasAllMappings(dto.getFields())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "All fields must have mappings when data mode is SYSTEM");
            problem.setTitle(Constants.INVALID_FIELD_MAPPINGS);
            return Either.left(problem);
        }
        if(dataMode.equals(DataMode.USER) && hasMappings(dto.getFields())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "No field mappings are allowed in a USER data mode template");
            problem.setTitle(Constants.INVALID_FIELD_MAPPINGS);
            return Either.left(problem);
        }
        return Either.right(null);
    }

    private boolean hasMappings(List<ReportTemplateFieldDto> fields) {
        boolean hasMappings = false;
        for (ReportTemplateFieldDto field : fields) {
            if(!field.getAccounts().isEmpty()) {
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
            if(field.getAccounts().isEmpty() && field.getChildFields().isEmpty()) {
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

    private Either<ProblemDetail, Void> validateReportTemplateDto(ReportTemplateDto dto) {
        Errors errors = validator.validateObject(dto);
        List<ObjectError> allErrors = new ArrayList<>(errors.getAllErrors());
        allErrors.addAll(getValidationErrorsOfFields(dto.getFields()));
        if (!allErrors.isEmpty()) {
            String errorDetails = allErrors.stream()
                    .map(ObjectError::getDefaultMessage)
                    .collect(Collectors.joining(", "));
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, errorDetails);
            problem.setTitle("Validation Error");
            return Either.left(problem);
        }
        return Either.right(null);
    }

    public Either<ProblemDetail, ReportTemplateResponseDto> update(ReportTemplateDto dto) {
        log.info("Updating report template: {}", dto.getName());
        Either<ProblemDetail, Void> validateReport = validateReport(dto);
        if (validateReport.isLeft()) return Either.left(validateReport.getLeft());

        // Check if the template to be updated exists
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndId(dto.getOrganisationId(), dto.getId());

        if (existingTemplateOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No template with id '" + dto.getId() + "' exists for this organisation. Use POST to create.");
            problem.setTitle("Template Not Found");
            return Either.left(problem);
        }

        ReportTemplateEntity existing = existingTemplateOpt.get();
        ReportTemplateEntity templateToSave;

        Either<ProblemDetail, Void> prohibitedFieldChanged = checkIfProhibitedFieldChanged(existing, dto);
        if(prohibitedFieldChanged.isLeft()) {
            return Either.left(prohibitedFieldChanged.getLeft());
        }

        // Check if there are any reports using this template
        List<ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(existing.getId());
        try {
            ReportTemplateType.valueOf(dto.getReportTemplateType());
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The report template type '" + dto.getReportTemplateType() + "' is not valid.");
            problem.setTitle("Invalid Report Template Type");
            return Either.left(problem);
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
        Either<ProblemDetail, Void> reportTypeValidated = reportTemplateTypeValidator.validateReportTemplateType(templateToSave);
        if(reportTypeValidated.isLeft()) {
            return Either.left(reportTypeValidated.getLeft());
        }

        ReportTemplateEntity saved = reportTemplateRepository.save(templateToSave);
        // Setting "old" version to inactive after save
        if(saved.getVer() > existing.getVer()) {
            existing.setActive(false);
            existing.setEditable(false);
            reportTemplateRepository.save(existing);
        }
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    private Either<ProblemDetail, Void> checkIfProhibitedFieldChanged(ReportTemplateEntity existing, ReportTemplateDto dto) {
        if(!existing.getName().equals(dto.getName())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Changing the name of a report template is not allowed. Please create a new template if you want a different name.");
            problem.setTitle("NAME_CHANGE_NOT_ALLOWED");
            return Either.left(problem);
        }
        if(!existing.getDataMode().name().equals(dto.getDataMode())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Changing the data mode of a report template is not allowed. Please create a new template if you want a different data mode.");
            problem.setTitle("DATA_MODE_CHANGE_NOT_ALLOWED");
            return Either.left(problem);
        }
        if(!existing.getReportTemplateType().name().equals(dto.getReportTemplateType())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Changing the report template type of a report template is not allowed. Please create a new template if you want a different report template type.");
            problem.setTitle("REPORT_TEMPLATE_TYPE_CHANGE_NOT_ALLOWED");
            return Either.left(problem);
        }
        return Either.right(null);
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
    public ReportTemplateListResponseDto findAll(String organisationId, String name, String description, List<ReportTemplateType> reportTemplateTypes, Boolean active, List<DataMode> dataMode, Pageable pageable) {
        Page<ReportTemplateEntity> findAllFiltered = reportTemplateRepository.findAll(organisationId, name, description, reportTemplateTypes, active, dataMode, pageable);
        List<ReportTemplateResponseDto> dtos = findAllFiltered.stream()
                .map(reportTemplateMapper::toResponseDto)
                .toList();
        return ReportTemplateListResponseDto.builder()
                .templates(dtos)
                .page(pageable.getPageNumber())
                .size(pageable.getPageSize())
                .total(findAllFiltered.getTotalElements())
                .totalPages(findAllFiltered.getTotalPages())
                .build();
    }

    public Either<ProblemDetail, Void> delete(String id) {
        log.info("Deleting report template id: {}", id);

        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(id);
        if (templateOpt.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Report template with ID " + id + " does not exist");
            problem.setTitle("TEMPLATE_NOT_FOUND");
            return Either.left(problem);
        }

        // Check if there are any reports using this template
        List<ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(id);

        if (!existingReports.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Cannot delete template with ID " + id + " because it has " +
                    existingReports.size() + " associated report(s)");
            problem.setTitle("TEMPLATE_IN_USE");
            return Either.left(problem);
        }

        reportTemplateRepository.deleteById(id);
        return Either.right(null);
    }

    @Transactional(readOnly = true)
    public boolean existsByOrganisationIdAndName(String organisationId, String name) {
        return reportTemplateRepository.existsByOrganisationIdAndName(organisationId, name);
    }

    private Either<ProblemDetail, Void> validateNoDuplicateFields(List<ReportTemplateFieldDto> fields) {
        Either<ProblemDetail, Void> duplicateFieldsNamesRecursive = validateNoDuplicateFieldsNamesRecursive(fields, null);
        if (duplicateFieldsNamesRecursive.isLeft()) {
            return duplicateFieldsNamesRecursive;
        }
        return Either.right(null);
    }

    private Either<ProblemDetail, Void> validateNoDuplicateFieldsNamesRecursive(List<ReportTemplateFieldDto> fields, String parentName) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Check for duplicates at this level
        Set<String> fieldNames = new HashSet<>();
        for (ReportTemplateFieldDto field : fields) {
            if (fieldNames.contains(field.getFieldName())) {
                String parentInfo = parentName != null ? " under parent '" + parentName + "'" : " at root level";
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Duplicate field name '" + field.getFieldName() + "'" + parentInfo + ". Field names must be unique within the same parent.");
                problem.setTitle("DUPLICATE_FIELD_NAME");
                return Either.left(problem);
            }
            fieldNames.add(field.getFieldName());

            // Recursively validate child fields
            if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
                Either<ProblemDetail, Void> childValidation = validateNoDuplicateFieldsNamesRecursive(
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

    private Either<ProblemDetail, Void> validateAccounts(List<ReportTemplateFieldDto> fields, String orgId) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Collect all subtype IDs from all fields (including nested)
        Set<String> allChartOfAccountCustomerCodes = new HashSet<>();
        collectSubTypeIds(fields, allChartOfAccountCustomerCodes);

        if (allChartOfAccountCustomerCodes.isEmpty()) {
            return Either.right(null);
        }

        // Convert String customer codes to Chart of Account IDs
        List<ChartOfAccount.Id> chartOfAccountIds = allChartOfAccountCustomerCodes.stream()
                .map(cc -> new ChartOfAccount.Id(orgId, cc))
                .toList();

        // Fetch existing subtypes

        List<String> existingSubTypeIds = chartOfAccountRepository.findAllById(chartOfAccountIds).stream()
                .map(coa -> coa.getId().getCustomerCode())
                .toList();

        // Find missing subtypes
        Set<String> missingAccounts = chartOfAccountIds.stream().map(ChartOfAccount.Id::getCustomerCode)
                .filter(id -> !existingSubTypeIds.contains(id))
                .collect(Collectors.toSet());

        if (!missingAccounts.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The following chart of account do not exist: " + missingAccounts);
            problem.setTitle("INVALID_ACCOUNTS");
            return Either.left(problem);
        }

        return Either.right(null);
    }

    private void collectSubTypeIds(List<ReportTemplateFieldDto> fields, Set<String> subTypeIds) {
        if (fields == null) {
            return;
        }

        for (ReportTemplateFieldDto field : fields) {
            if (field.getAccounts() != null) {
                subTypeIds.addAll(field.getAccounts());
            }
            if (field.getChildFields() != null) {
                collectSubTypeIds(field.getChildFields(), subTypeIds);
            }
        }
    }

    private Either<ProblemDetail, Void> validateValidationRules(ReportTemplateDto dto) {
        if (dto.getValidationRules() == null || dto.getValidationRules().isEmpty()) {
            return Either.right(null);
        }

        // Collect all field names from the template
        Set<String> allFieldNames = new HashSet<>();
        collectFieldNames(dto.getFields(), allFieldNames);

        for (ValidationRuleDto rule : dto.getValidationRules()) {
            // Validate rule has a name
            if (rule.getName() == null || rule.getName().trim().isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation rule must have a name");
                problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                return Either.left(problem);
            }

            // Validate operator
            try {
                ComparisonOperator.valueOf(rule.getOperator());
            } catch (IllegalArgumentException e) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid comparison operator: " + rule.getOperator() + ". Must be one of: GREATER_THAN_OR_EQUAL, EQUAL, LESS_THAN_OR_EQUAL");
                problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                return Either.left(problem);
            }

            // Validate left side terms
            if (rule.getLeftSideTerms() == null || rule.getLeftSideTerms().isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, Constants.VALIDATION_RULE_S_MUST_HAVE_AT_LEAST_ONE_TERM_ON_THE_S_SIDE.formatted(rule.getName(), "left"));
                problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                return Either.left(problem);
            }

            // Validate right side terms
            if (rule.getRightSideTerms() == null || rule.getRightSideTerms().isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, Constants.VALIDATION_RULE_S_MUST_HAVE_AT_LEAST_ONE_TERM_ON_THE_S_SIDE.formatted(rule.getName(), "right"));
                problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                return Either.left(problem);
            }

            // Validate all terms reference existing fields
            List<ValidationRuleTermDto> allTerms = new ArrayList<>();
            allTerms.addAll(rule.getLeftSideTerms());
            allTerms.addAll(rule.getRightSideTerms());

            for (ValidationRuleTermDto term : allTerms) {
                if (term.getFieldName() == null || term.getFieldName().trim().isEmpty()) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation Rule term must have names.");
                    problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                    return Either.left(problem);
                }
                List<String> namesNotExist = Arrays.stream(term.getFieldName().split("\\.")).filter(s -> !allFieldNames.contains(s)).toList();
                if (!namesNotExist.isEmpty()) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation rule '" + rule.getName() + "' references field names '" + namesNotExist + "' which do not exist in the template");
                    problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                    return Either.left(problem);
                }

                // Validate operation
                try {
                    TermOperation.valueOf(term.getOperation());
                } catch (IllegalArgumentException e) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid term operation: " + term.getOperation() + ". Must be one of: ADD, SUBTRACT");
                    problem.setTitle(Constants.INVALID_VALIDATION_RULE);
                    return Either.left(problem);
                }
            }
        }

        Set<String> validationRuleNames = new HashSet<>();
        Optional<ValidationRuleDto> duplicateName = dto.getValidationRules().stream().filter(rule -> !validationRuleNames.add(rule.getName())).findFirst();
        if(duplicateName.isPresent()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Duplicate validation rule name found: '" + duplicateName.get().getName() + "'. Each validation rule must have a unique name.");
            problem.setTitle(Constants.INVALID_VALIDATION_RULE);
            return Either.left(problem);
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
