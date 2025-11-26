package org.cardanofoundation.lob.app.reporting.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

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

import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountType;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.TemplateCsvLine;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    private final ReportingRepository reportingRepository;
    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final AntiVirusScanner antiVirusScanner;
    private final CsvParser<TemplateCsvLine> csvParser;
    private final Validator validator;
    private final ChartOfAccountTypeRepository chartOfAccountTypeRepository;
    private final ChartOfAccountSubTypeRepository subTypeRepository;

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


        // New template - create with version 1
        log.info("Creating new template: {}", dto.getName());
        ReportTemplateEntity templateToSave = reportTemplateMapper.toEntity(dto, null);
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
                    .withTitle("Report Template Not Found")
                    .withDetail("Report template with ID " + id + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        // Check if there are any reports using this template
        List<org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(id);

        if (!existingReports.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Template Has Associated Reports")
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
            .collect(Collectors.toList());

        // Fetch existing subtypes
        List<String> existingSubTypeIds = chartOfAccountSubTypeRepository.findAllById(stringIds).stream()
                .map(subType -> String.valueOf(subType.getId()))
                .collect(Collectors.toList());

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

    public Either<Problem, List<ReportTemplateResponseDto>> createCsvTemplates(@Valid CreateCsvTemplateRequest csvTemplateRequest) {
        Optional<Organisation> organisationO = organisationPublicApiIF.findByOrganisationId(csvTemplateRequest.getOrganisationId());
        if (organisationO.isEmpty()) {
            Problem problem = Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail("Organisation with id " + csvTemplateRequest.getOrganisationId() + " not found.")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
            return Either.left(problem);
        }
        Either<Problem, List<TemplateCsvLine>> parsedLines = csvParser.parseCsv(
                csvTemplateRequest.getFile(), TemplateCsvLine.class);
        if (parsedLines.isLeft()) {
            return Either.left(parsedLines.getLeft());
        }
        List<TemplateCsvLine> templateCsvLines = new ArrayList<>(parsedLines.get());
        Either<List<Problem>, Void> validationResult = validateTemplateCsvLines(templateCsvLines);
        if (validationResult.isLeft()) {
            return Either.left(validationResult.getLeft().get(0));
        }
        List<Either<Problem, ReportTemplateDto>> results = new ArrayList<>();
        outerLoop:
        while (!templateCsvLines.isEmpty()) {
            TemplateCsvLine firstLine = templateCsvLines.getFirst();
            List<TemplateCsvLine> filteredLines = templateCsvLines.stream()
                    .filter(line -> line.getName().equals(firstLine.getName()) && line.getReportType().equals(firstLine.getReportType()))
                    .toList();
            templateCsvLines.removeAll(filteredLines);
            ReportTemplateType reportTemplateType;
            try {
                reportTemplateType = ReportTemplateType.valueOf(firstLine.getReportType());
            } catch (IllegalArgumentException e) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("Invalid report template type: " + firstLine.getReportType() + ". Options are: " + String.join(", ", Arrays.stream(ReportTemplateType.values()).map(Enum::name).toList()))
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                results.add(Either.left(problem));
                continue;
            }
            ReportTemplateDto reportTemplateDto = new ReportTemplateDto();
            reportTemplateDto.setOrganisationId(csvTemplateRequest.getOrganisationId());
            reportTemplateDto.setName(firstLine.getName());
            reportTemplateDto.setReportTemplateType(reportTemplateType);
            reportTemplateDto.setVer(1L);
            reportTemplateRepository.findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion(reportTemplateDto.getOrganisationId(),
                            reportTemplateDto.getName(), reportTemplateDto.getReportTemplateType())
                    .ifPresent(existingTemplate -> reportTemplateDto.setVer(existingTemplate.getVer() + 1));
            List<ReportTemplateFieldDto> fieldDtos = new ArrayList<>();
            for (TemplateCsvLine templateCsvLine : filteredLines) {
                Either<Problem, ReportTemplateFieldDto> fieldEntityResult = csvLineToTemplateField(csvTemplateRequest.getOrganisationId(), templateCsvLine);
                if (fieldEntityResult.isLeft()) {
                    results.add(Either.left(fieldEntityResult.getLeft()));
                    break outerLoop;
                }
                ReportTemplateFieldDto fieldDto = fieldEntityResult.get();
                if (!templateCsvLine.getParent().isEmpty()) {
                    Optional<ReportTemplateFieldDto> parentFieldO = fieldDtos.stream()
                            .filter(fe -> fe.getFieldName().equals(templateCsvLine.getParent()))
                            .findFirst();
                    if (parentFieldO.isEmpty()) {
                        Problem problem = Problem.builder()
                                .withTitle(Constants.CSV_PARSING_ERROR)
                                .withDetail("Parent field not found: " + templateCsvLine.getParent() + " for field: " + templateCsvLine.getFieldName() + ". Note: The parent field must be defined before the child field in the CSV.")
                                .withStatus(Status.BAD_REQUEST)
                                .build();
                        results.add(Either.left(problem));
                        break outerLoop;
                    }
                    ReportTemplateFieldDto parentField = parentFieldO.get();
                    if (parentField.getChildFields() == null) {
                        parentField.setChildFields(new ArrayList<>());
                    }
                    Optional<ReportTemplateFieldDto> childWithSameName = parentField.getChildFields().stream().filter(field -> field.getFieldName().equals(fieldDto.getFieldName())).findFirst();
                    if(childWithSameName.isPresent()) {
                        Problem problem = Problem.builder()
                                .withTitle(Constants.CSV_PARSING_ERROR)
                                .withDetail("Duplicate field name under the same parent: " + fieldDto.getFieldName() + " under parent: " + parentField.getFieldName())
                                .withStatus(Status.BAD_REQUEST)
                                .build();
                        results.add(Either.left(problem));
                        break outerLoop;
                    }
                    parentField.getChildFields().add(fieldDto);
                }

                fieldDtos.add(fieldDto);
            }
            reportTemplateDto.setFields(fieldDtos);
            results.add(Either.right(reportTemplateDto));
        }
        return Either.right(results.stream().map(e -> e.fold(
                left -> ReportTemplateResponseDto.builder().error(Optional.of(left)).build(),
                dto -> {
                    ReportTemplateEntity existingTemplate = reportTemplateRepository.findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion(dto.getOrganisationId(), dto.getName(), dto.getReportTemplateType()).orElse(null);
                    ReportTemplateEntity entity = reportTemplateMapper.toEntity(dto, existingTemplate);
                    entity = reportTemplateRepository.save(entity);
                    return reportTemplateMapper.toResponseDto(entity);
                }
        )).toList());
    }

    private Either<Problem, ReportTemplateFieldDto> csvLineToTemplateField(String organisationId, TemplateCsvLine templateCsvLine) {
        ReportTemplateFieldDto fieldEntity = new ReportTemplateFieldDto();
        fieldEntity.setFieldName(templateCsvLine.getFieldName());
        fieldEntity.setAccumulated(templateCsvLine.getAccumulated());
        fieldEntity.setAccumulatedYearly(templateCsvLine.getAccumulatedYearly());
        fieldEntity.setAccumulatedPreviousYear(templateCsvLine.getAccumulatedPreviousYear());
        fieldEntity.setNegated(templateCsvLine.getNegated());
        String[] mappedAccounts = templateCsvLine.getTypes().split(";");
        List<ChartOfAccountSubType> mappedSubTypes = new ArrayList<>();
        for (String mappedAccount : mappedAccounts) {
            String[] typeSubTypeSplit = mappedAccount.split("_");
            if (mappedAccount.isBlank()) {
                continue;
            } else if (typeSubTypeSplit.length != 2) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("Invalid chart of account mapping: " + mappedAccount + ". Expected format 'TYPE_SUBTYPE' and semicolon seperated.")
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                return Either.left(problem);
            }
            String typeName = typeSubTypeSplit[0];
            String subTypeName = typeSubTypeSplit[1];
            Optional<ChartOfAccountType> accountTypeO = chartOfAccountTypeRepository.findFirstByOrganisationIdAndName(organisationId, typeName);
            if (accountTypeO.isEmpty()) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("Chart of account type not found: " + typeName)
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                return Either.left(problem);
            }
            Optional<ChartOfAccountSubType> subtypeO = accountTypeO.get().getSubTypes().stream().filter(subType -> subType.getName().equals(subTypeName)).findFirst();
            if (subtypeO.isEmpty()) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("Chart of account subtype not found: " + subTypeName + " for type: " + typeName)
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                return Either.left(problem);
            }
            mappedSubTypes.add(subtypeO.get());
        }
        fieldEntity.setMappingSubTypeIds(mappedSubTypes.stream().map(ChartOfAccountSubType::getId).collect(Collectors.toList()));
        return Either.right(fieldEntity);
    }

    private Either<List<Problem>, Void> validateTemplateCsvLines(List<TemplateCsvLine> reportCsvLines) {
        List<Problem> problems = new ArrayList<>();
        for (TemplateCsvLine templateCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(templateCsvLine);
            List<ObjectError> allErrors = validateObject.getAllErrors();
            if (!allErrors.isEmpty()) {
                Problem error = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                        .withStatus(Status.BAD_REQUEST)
                        .build();
                problems.add(error);
            }
        }
        if (problems.size() > 0) {
            return Either.left(problems);
        }
        return Either.right(null);
    }
}
