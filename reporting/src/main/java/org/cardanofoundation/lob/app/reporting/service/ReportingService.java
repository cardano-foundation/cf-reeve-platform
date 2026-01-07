package org.cardanofoundation.lob.app.reporting.service;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.SYSTEM;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxStatusUpdate;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.Account;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.TransactionRepositoryGateway;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportGenerateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportPublishRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.events.PublishReportEvent;
import org.cardanofoundation.lob.app.reporting.mapper.ReportMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateValidationRuleEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ValidationRuleTermEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.TermSide;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.util.Constants;
import org.cardanofoundation.lob.app.reporting.util.Helper;
import org.cardanofoundation.lob.app.support.security.AuthenticationUserService;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportingService {

    private final ReportingRepository reportRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportMapper reportMapper;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final AuthenticationUserService authenticationUserService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionRepositoryGateway transactionRepositoryGateway;

    public ReportResponseDto create(ReportDto dto) {
        log.info("Creating report: {}", dto.getName());

        // Validate dataMode is provided and valid
        DataMode dataMode = validateAndParseDataMode(dto.getDataMode());
        if (dataMode == null) {
            return ReportResponseDto.builder().error(Optional.of(Helper.buildDataModeError(dto.getDataMode()))).build();
        }

        // Validate report template exists and organization matches
        Either<Problem, ReportTemplateEntity> templateResult = validateTemplate(dto.getReportTemplateId(), dto.getOrganisationId());
        if (templateResult.isLeft()) {
            return ReportResponseDto.builder().error(Optional.of(templateResult.getLeft())).build();
        }
        ReportTemplateEntity template = templateResult.get();
        if(dataMode != template.getDataMode()) {
            return ReportResponseDto.builder().error(Optional.of(
                    Problem.builder()
                            .withTitle("DATA_MODE_MISMATCH")
                            .withDetail("Data mode of the report (" + dataMode.name() + ") does not match the template's data mode (" + template.getDataMode().name() + ")")
                            .withStatus(Status.BAD_REQUEST)
                            .build()
            )).build();
        }

        // Check if template is active
        if (!template.isActive()) {
            return ReportResponseDto.builder().error(Optional.of(
                    Problem.builder()
                            .withTitle("TEMPLATE_INACTIVE")
                            .withDetail("Report template with ID %s is inactive and cannot be used to create reports".formatted(dto.getReportTemplateId()))
                            .withStatus(Status.BAD_REQUEST)
                            .build()
            )).build();
        }

        // Validating Time in DTO
        Either<Problem, Void> timeValidation = validateTimeInDto(dto.getYear(), dto.getPeriod(), dto.getIntervalType());
        if (timeValidation.isLeft()) {
            return ReportResponseDto.builder().error(Optional.of(timeValidation.getLeft())).build();
        }
        if (dataMode == DataMode.SYSTEM && dto.getFields() != null && !dto.getFields().isEmpty()) {
            return ReportResponseDto.builder().error(Optional.of(
                    Problem.builder()
                            .withTitle("FIELDS_NOT_ALLOWED")
                            .withDetail("Fields should not be provided when dataMode is SYSTEM")
                            .withStatus(Status.BAD_REQUEST)
                            .build()
            )).build();
        }

        // Handle field generation based on data mode
        Either<Problem, List<ReportFieldDto>> fieldsResult = generateOrValidateFields(dto, dataMode, template);
        if (fieldsResult.isLeft()) {
            return ReportResponseDto.builder().error(Optional.of(fieldsResult.getLeft())).build();
        }
        List<ReportFieldDto> fields = fieldsResult.get();

        // Update dto with generated or validated fields
        ReportDto reportDtoWithFields = buildReportDtoWithFields(dto, fields);

        // Check if a report already exists for the same template, interval, year, and period
        ReportEntity existingReport = findExistingReport(dto);

        ReportEntity entity = reportMapper.toEntity(reportDtoWithFields, existingReport, template);

        // Handle versioning: if overwriting a published report, increment version
        handleVersioning(entity, dto, existingReport);

        List<ReportTemplateValidationRuleEntity> notPassedValidationRules = findNotPassedValidationRules(entity);
        if (!notPassedValidationRules.isEmpty()) {
            entity.setFailedValidationRules(notPassedValidationRules);
            entity.setReadyToPublish(false);
        } else {
            entity.setFailedValidationRules(new ArrayList<>());
            entity.setReadyToPublish(true);
        }

        entity = reportRepository.save(entity);
        return reportMapper.toResponseDto(entity);
    }

    private List<ReportTemplateValidationRuleEntity> findNotPassedValidationRules(ReportEntity entity) {
        ReportTemplateEntity reportTemplate = entity.getReportTemplate();
        List<ReportTemplateValidationRuleEntity> validationRules = reportTemplate.getValidationRules();
        List<ReportTemplateValidationRuleEntity> failedRules = new ArrayList<>();
        for (ReportTemplateValidationRuleEntity rule : validationRules) {
            // Skip inactive validation rules
            if (!rule.isActive()) {
                continue;
            }

            List<ValidationRuleTermEntity> allTerms = rule.getTerms();
            List<ValidationRuleTermEntity> leftSide = allTerms.stream().filter(ruleTerm -> ruleTerm.getSide().equals(TermSide.LEFT)).toList();
            BigDecimal leftValue = getValueFromValidationRuleTerms(entity, leftSide);
            List<ValidationRuleTermEntity> rightSide = allTerms.stream().filter(ruleTerm -> ruleTerm.getSide().equals(TermSide.RIGHT)).toList();
            BigDecimal rightValue = getValueFromValidationRuleTerms(entity, rightSide);

            int cmp = leftValue.compareTo(rightValue);

            switch (rule.getOperator()) {
                case EQUAL -> {
                    if (cmp != 0) failedRules.add(rule);
                }
                case GREATER_THAN_OR_EQUAL -> {
                    if (cmp < 0) failedRules.add(rule);
                }
                case LESS_THAN_OR_EQUAL -> {
                    if (cmp > 0) failedRules.add(rule);
                }
            }

        }
        return failedRules;
    }

    private BigDecimal getValueFromValidationRuleTerms(ReportEntity entity, List<ValidationRuleTermEntity> terms) {
        BigDecimal result = BigDecimal.ZERO;
        terms = terms.stream().sorted(Comparator.comparingInt(ValidationRuleTermEntity::getTermOrder)).toList();
        for (ValidationRuleTermEntity term : terms) {
            BigDecimal fieldValue = findFieldValueRecursively(entity.getFields(), term.getField().getId());
            switch (term.getOperation()) {
                case ADD -> result = result.add(fieldValue);
                case SUBTRACT -> result = result.subtract(fieldValue);
            }
        }
        return result;
    }

    private BigDecimal findFieldValueRecursively(List<ReportFieldEntity> fields, Long templateFieldId) {
        for (ReportFieldEntity field : fields) {

            if (field.getFieldTemplate().getId().equals(templateFieldId)) {
                return field.getValue() != null
                        ? field.getValue()
                        : BigDecimal.ZERO;
            }

            if (field.getChildFields() != null && !field.getChildFields().isEmpty()) {
                BigDecimal childValue =
                        findFieldValueRecursively(field.getChildFields(), templateFieldId);

                if (childValue != null) {
                    return childValue;
                }
            }
        }

        return null; // ← important
    }

    /**
     * This function validates the time-related fields in the ReportDto.
     * It checks if intervalTypeStr is a valid Enum object and if period and year are within acceptable ranges.
     *
     * @return Either a ReportResponseDto with error details if validation fails, or Void if validation passes.
     */
    private Either<Problem, Void> validateTimeInDto(short year, short period, String intervalTypeStr) {
        IntervalType intervalType;
        try {
            intervalType = IntervalType.valueOf(intervalTypeStr);
        } catch (IllegalArgumentException e) {
            return Either.left(
                    Problem.builder()
                            .withTitle("INVALID_INTERVAL_TYPE")
                            .withDetail("Interval type must be one of: MONTH, QUARTER, YEAR")
                            .withStatus(Status.BAD_REQUEST)
                            .build());
        }
        LocalDate now = LocalDate.now();
        if (year > now.getYear()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_IN_FUTURE)
                    .withDetail("Report year cannot be in the future")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        if (intervalType == IntervalType.MONTH) {
            if (period < 1 || period > 12) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_PERIOD)
                        .withDetail("Report month must be between 1 and 12")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            if (year == now.getYear() && period > now.getMonthValue() - 1) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.REPORT_IN_FUTURE)
                        .withDetail("Report month cannot be in the future")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
        }
        if (intervalType == IntervalType.QUARTER) {
            if (period < 1 || period > 4) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_PERIOD)
                        .withDetail("Report quarter must be between 1 and 4")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            if (year == now.getYear()) {
                int currentQuarter = (now.getMonthValue() - 1) / 3;
                if (period > currentQuarter) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.REPORT_IN_FUTURE)
                            .withDetail("Report quarter cannot be in the future")
                            .withStatus(Status.BAD_REQUEST)
                            .build());
                }
            }
        }
        return Either.right(null);
    }

    public Either<Problem, ReportResponseDto> generate(ReportGenerateRequest request) {
        log.info("Generating report preview for template: {}, org: {}, interval: {}, year: {}, period: {}",
                request.getReportTemplateId(), request.getOrganisationId(),
                request.getIntervalType(), request.getYear(), request.getPeriod());

        // Validate report template exists and organization matches
        Either<Problem, ReportTemplateEntity> templateResult = validateTemplate(request.getReportTemplateId(), request.getOrganisationId());
        if (templateResult.isLeft()) {
            return Either.left(templateResult.getLeft());
        }
        ReportTemplateEntity template = templateResult.get();

        // Check if template is active
        if (!template.isActive()) {
            return Either.left(Problem.builder()
                    .withTitle("TEMPLATE_INACTIVE")
                    .withDetail("Report template with ID %s is inactive and cannot be used to generate reports".formatted(request.getReportTemplateId()))
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // Validating Time in DTO
        Either<Problem, Void> timeValidation = validateTimeInDto(request.getYear(), request.getPeriod(), request.getIntervalType());
        if (timeValidation.isLeft()) {
            return Either.left(timeValidation.getLeft());
        }
        // Calculate date range and generate fields
        IntervalType intervalType = IntervalType.valueOf(request.getIntervalType());
        Short periodValue = request.getPeriod();
        short period = (periodValue != null) ? periodValue : (short) 1;
        LocalDate startDate = getReportStartDate(intervalType, period, request.getYear());
        LocalDate endDate = getReportEndDate(intervalType, startDate);

        List<ReportFieldDto> fields = fillFieldsFromTemplate(template.getFields(), startDate, endDate, request.isPreview());

        // Generate report name
        String reportName = generateReportName(template.getName(), request.getIntervalType(), request.getYear(), request.getPeriod());

        // Create report DTO for preview (not saved)
        ReportDto reportDto = ReportDto.builder()
                .reportTemplateId(request.getReportTemplateId())
                .name(reportName)
                .intervalType(request.getIntervalType())
                .year(request.getYear())
                .period(request.getPeriod())
                .dataMode(SYSTEM.name())
                .fields(fields)
                .build();
        reportDto.setOrganisationId(request.getOrganisationId());

        // Map to entity without saving to get the response structure
        ReportEntity previewEntity = reportMapper.toEntity(reportDto, null, template);

        // Return the preview without saving
        return Either.right(reportMapper.toResponseDto(previewEntity));
    }

    private DataMode validateAndParseDataMode(String dataModeStr) {
        if (dataModeStr == null || dataModeStr.isBlank()) {
            return null;
        }
        try {
            return DataMode.valueOf(dataModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Either<Problem, ReportTemplateEntity> validateTemplate(String templateId, String organisationId) {
        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(templateId);
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_TEMPLATE_NOT_FOUND")
                    .withDetail("Report template with ID %s does not exist".formatted(templateId))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportTemplateEntity template = templateOpt.get();
        if (!template.getOrganisationId().equals(organisationId)) {
            return Either.left(Problem.builder()
                    .withTitle("ORGANISATION_MISMATCH")
                    .withDetail("Report template belongs to a different organisation")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        return Either.right(template);
    }

    private Either<Problem, List<ReportFieldDto>> generateOrValidateFields(ReportDto dto, DataMode
            dataMode, ReportTemplateEntity template) {
        if (dataMode == DataMode.SYSTEM) {
            return generateFieldsForReport(dto, template);
        } else {
            return validateUserFields(dto.getFields(), template);
        }
    }

    private Either<Problem, List<ReportFieldDto>> generateFieldsForReport(ReportDto dto, ReportTemplateEntity
            template) {
        IntervalType intervalType = IntervalType.valueOf(dto.getIntervalType());
        Short periodValue = dto.getPeriod();
        short period = (periodValue != null) ? periodValue : (short) 1;
        LocalDate startDate = getReportStartDate(intervalType, period, dto.getYear());
        LocalDate endDate = getReportEndDate(intervalType, startDate);

        List<ReportFieldDto> fields = fillFieldsFromTemplate(template.getFields(), startDate, endDate, false);
        return Either.right(fields);
    }

    private Either<Problem, List<ReportFieldDto>> validateUserFields
            (List<ReportFieldDto> fields, ReportTemplateEntity template) {
        if (fields == null || fields.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.MISSING_REQUIRED_FIELDS)
                    .withDetail("USER reports must have fields specified.")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // Validate template fields exist
        Either<Problem, Void> fieldValidation = validateTemplateFields(fields, template.getFields());
        if (fieldValidation.isLeft()) {
            return Either.left(fieldValidation.getLeft());
        }

        return Either.right(fields);
    }

    private ReportDto buildReportDtoWithFields(ReportDto dto, List<ReportFieldDto> fields) {
        ReportDto reportDto = ReportDto.builder()
                .reportTemplateId(dto.getReportTemplateId())
                .name(dto.getName())
                .intervalType(dto.getIntervalType())
                .period(dto.getPeriod())
                .year(dto.getYear())
                .dataMode(dto.getDataMode())
                .fields(fields)
                .build();
        reportDto.setOrganisationId(dto.getOrganisationId());
        return reportDto;
    }

    private ReportEntity findExistingReport(ReportDto dto) {
        if (dto.getIntervalType() == null || dto.getYear() == null || dto.getPeriod() == null) {
            return null;
        }

        IntervalType intervalType = IntervalType.valueOf(dto.getIntervalType());
        Optional<ReportEntity> existingReportOpt = reportRepository.findLatestByTemplateAndPeriod(
                dto.getOrganisationId(),
                dto.getReportTemplateId(),
                intervalType,
                dto.getYear(),
                dto.getPeriod()
        );

        if (existingReportOpt.isEmpty()) {
            return null;
        }

        ReportEntity existingReport = existingReportOpt.get();

        // If the existing report is already published, create a new version
        if (existingReport.isLedgerDispatchApproved()) {
            log.info("Existing report is published, creating new version {} -> {}",
                    existingReport.getVer(), existingReport.getVer() + 1);
            return null; // Will create new entity with incremented version
        }

        log.info("Overwriting existing unpublished report with ID: {}", existingReport.getId());
        return existingReport;
    }

    private void handleVersioning(ReportEntity entity, ReportDto dto, ReportEntity existingReport) {
        if (existingReport != null || dto.getIntervalType() == null || dto.getYear() == null || dto.getPeriod() == null) {
            return;
        }

        IntervalType intervalType = IntervalType.valueOf(dto.getIntervalType());
        Optional<ReportEntity> latestPublishedOpt = reportRepository.findLatestByTemplateAndPeriod(
                dto.getOrganisationId(),
                dto.getReportTemplateId(),
                intervalType,
                dto.getYear(),
                dto.getPeriod()
        );

        if (latestPublishedOpt.isPresent() && latestPublishedOpt.get().isLedgerDispatchApproved()) {
            entity.setVer(latestPublishedOpt.get().getVer() + 1);
        }
    }

    private String generateReportName(String templateName, String intervalType, Short year, Short period) {
        return String.format("%s - %s %d%s",
                templateName,
                intervalType,
                year,
                period != null ? " Period " + period : "");
    }

    /**
     * Validates template fields recursively.
     * Checks:
     * 1. All field IDs exist in the template at the correct hierarchy level
     * 2. Fields with children must NOT have a value set
     * 3. Field hierarchy matches template hierarchy
     */
    private Either<Problem, Void> validateTemplateFields(List<ReportFieldDto> columns, List<ReportTemplateFieldEntity> templateColumns) {
        if (columns == null || columns.isEmpty()) {
            return Either.right(null);
        }

        return validateTemplateFieldsRecursive(columns, templateColumns, "");
    }

    /**
     * Recursively validates report fields against template fields.
     *
     * @param reportFields   The report fields to validate
     * @param templateFields The template fields to validate against
     * @param pathPrefix     Path prefix for error messages (e.g., "Income.Revenue")
     * @return Either a Problem if validation fails, or void on success
     */
    private Either<Problem, Void> validateTemplateFieldsRecursive(
            List<ReportFieldDto> reportFields,
            List<ReportTemplateFieldEntity> templateFields,
            String pathPrefix) {

        if (reportFields == null || reportFields.isEmpty()) {
            return Either.right(null);
        }

        // Create a map of template fields for quick lookup
        Map<Long, ReportTemplateFieldEntity> templateFieldMap = templateFields.stream()
                .collect(Collectors.toMap(ReportTemplateFieldEntity::getId, field -> field));

        for (ReportFieldDto reportField : reportFields) {
            // Check if template field ID is provided
            if (reportField.getTemplateFieldId() == null) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.MISSING_TEMPLATE_ID)
                        .withDetail("Report field at path '" + pathPrefix + "' is missing template field ID")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Check if template field exists at this level
            ReportTemplateFieldEntity templateField = templateFieldMap.get(reportField.getTemplateFieldId());
            if (templateField == null) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_TEMPLATE_FIELD_ID)
                        .withDetail("Template field with ID " + reportField.getTemplateFieldId() +
                                " does not exist at path '" + pathPrefix + "'")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            String currentPath = pathPrefix.isEmpty() ? templateField.getName() : pathPrefix + "." + templateField.getName();

            // Check if field has children
            boolean hasChildren = templateField.getChildFields() != null && !templateField.getChildFields().isEmpty();

            // Validate: fields with children must NOT have a value
            if (hasChildren && reportField.getValue() != null) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.INVALID_FIELD_VALUE)
                        .withDetail("Field '%s' has child fields and must not have a value set. Only leaf fields (fields without children) can have values.".formatted(currentPath))
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Recursively validate child fields
            if (hasChildren) {
                List<ReportTemplateFieldEntity> templateChildFields = templateField.getChildFields();

                if (templateChildFields == null || templateChildFields.isEmpty()) {
                    return Either.left(Problem.builder()
                            .withTitle(Constants.INVALID_FIELD_STRUCTURE)
                            .withDetail("Field '" + currentPath + "' has child fields in the report, " +
                                    "but the template field has no children defined")
                            .withStatus(Status.BAD_REQUEST)
                            .build());
                }

                Either<Problem, Void> childValidation = validateTemplateFieldsRecursive(
                        reportField.getChildFields(),
                        templateChildFields,
                        currentPath
                );

                if (childValidation.isLeft()) {
                    return childValidation;
                }
            }
        }

        return Either.right(null);
    }


    @Transactional(readOnly = true)
    public Optional<ReportResponseDto> findById(String id) {
        return reportRepository.findById(id)
                .map(reportMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public Optional<ReportResponseDto> findByOrganisationIdAndId(String organisationId, String id) {
        return reportRepository.findByOrganisationIdAndId(organisationId, id)
                .map(reportMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public List<ReportResponseDto> findByOrganisationId(String organisationId) {
        return reportRepository.findByOrganisationId(organisationId).stream()
                .map(reportMapper::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportResponseDto> findByReportTemplateId(String reportTemplateId) {
        return reportRepository.findByReportTemplateId(reportTemplateId).stream()
                .map(reportMapper::toResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportResponseDto> findAll() {
        return reportRepository.findAll().stream()
                .map(reportMapper::toResponseDto)
                .toList();
    }

    public Either<Problem, Void> delete(String id) {
        log.info("Deleting report id: {}", id);

        Optional<ReportEntity> reportOpt = reportRepository.findById(id);
        if (reportOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(id))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportEntity report = reportOpt.get();

        // Check if report is already published
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_ALREADY_PUBLISHED)
                    .withDetail("Cannot delete report with ID %s because it has already been published".formatted(id))
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        reportRepository.deleteById(id);
        return Either.right(null);
    }

    private List<ReportFieldDto> fillFieldsFromTemplate
            (List<ReportTemplateFieldEntity> templateFields, LocalDate startDate, LocalDate endDate, boolean preview) {
        if (templateFields == null) {
            return null;
        }

        return templateFields.stream()
                .filter(field -> field.getParentField() == null) // Only top-level fields
                .map(field -> fillTemplateFieldRecursively(field, startDate, endDate, preview))
                .toList();
    }

    private ReportFieldDto fillTemplateFieldRecursively(ReportTemplateFieldEntity templateField, LocalDate
            startDate, LocalDate endDate, boolean preview) {
        List<ReportFieldDto> childColumns = null;

        if (templateField.getChildFields() != null && !templateField.getChildFields().isEmpty()) {
            // Has children - recursively fill child fields
            childColumns = templateField.getChildFields().stream()
                    .map(child -> fillTemplateFieldRecursively(child, startDate, endDate, preview))
                    .toList();
        }

        // Calculate value based on mapping types (if no children or if it's an accumulated field)
        BigDecimal value = null;
        if (templateField.getMappingTypes() != null && !templateField.getMappingTypes().isEmpty()) {
            value = calculateFieldValue(templateField, startDate, endDate, preview);
        }

        return ReportFieldDto.builder()
                .templateFieldId(templateField.getId())
                .templateFieldName(templateField.getName())
                .value(value)
                .childFields(childColumns)
                .build();
    }

    private BigDecimal calculateFieldValue(ReportTemplateFieldEntity field, LocalDate startDate, LocalDate endDate, boolean preview) {

        LocalDate effectiveStartDate = getEffectiveStartDate(field, startDate);
        LocalDate effectiveEndDate = getEffectiveEndDate(field, startDate, endDate);
        BigDecimal totalAmount = BigDecimal.ZERO;

        // Get all chart of accounts mapped to this field's subtypes
        if (field.getMappingTypes() != null && !field.getMappingTypes().isEmpty()) {
            Set<ChartOfAccount> chartOfAccounts = getAllChartOfAccounts(field);
            totalAmount = addOpeningBalances(chartOfAccounts, effectiveStartDate, effectiveEndDate, totalAmount);
            // Get account codes for transaction lookup
            List<String> accountCodes = chartOfAccounts.stream()
                    .map(coa -> coa.getId().getCustomerCode())
                    .toList();

            if (!accountCodes.isEmpty()) {
                // Query transaction items for these accounts in the date range
                List<TransactionItemEntity> transactionItems = getTransactionItems(preview, accountCodes, effectiveStartDate, effectiveEndDate);

                // Map accounts for quick lookup
                Map<String, ChartOfAccount> accountMap = chartOfAccounts.stream()
                        .collect(Collectors.toMap(
                                coa -> coa.getId().getCustomerCode(),
                                coa -> coa
                        ));

                totalAmount = SumTransactionItems(transactionItems, accountMap, totalAmount);
            }
        }

        // Apply negation if configured
        if (field.isNegated()) {
            totalAmount = totalAmount.negate();
        }

        return totalAmount.stripTrailingZeros();
    }

    private List<TransactionItemEntity> getTransactionItems(boolean preview, List<String> accountCodes, LocalDate effectiveStartDate, LocalDate effectiveEndDate) {
        List<TransactionItemEntity> transactionItems;
        if(preview) {
            transactionItems = transactionItemRepository
                    .findPreviewTransactionItemsByAccountCodeAndDateRange(accountCodes, effectiveStartDate, effectiveEndDate);
        } else {
            transactionItems = transactionItemRepository
                .findTransactionItemsByAccountCodeAndDateRange(accountCodes, effectiveStartDate, effectiveEndDate);
        }
        return transactionItems;
    }

    private Set<ChartOfAccount> getAllChartOfAccounts(ReportTemplateFieldEntity field) {
        List<Long> subTypeIds = field.getMappingTypes().stream()
                .map(org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType::getId)
                .collect(Collectors.toList());

        return chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(subTypeIds);
    }

    private static LocalDate getEffectiveEndDate(ReportTemplateFieldEntity field, LocalDate startDate, LocalDate endDate) {
        LocalDate effectiveEndDate = endDate;
        if (field.isAccumulatedPreviousYear()) {
            effectiveEndDate = LocalDate.of(startDate.getYear() - 1, 12, 31);
        }
        return effectiveEndDate;
    }

    private static LocalDate getEffectiveStartDate(ReportTemplateFieldEntity field, LocalDate startDate) {
        // Adjust date range based on accumulation flags
        LocalDate effectiveStartDate = startDate;
        if (field.isAccumulated()) {
            effectiveStartDate = LocalDate.EPOCH;
        }
        if (field.isAccumulatedYearly()) {
            effectiveStartDate = LocalDate.of(startDate.getYear(), 1, 1);
        }
        if (field.isAccumulatedPreviousYear()) {
            if (!field.isAccumulated()) {
                effectiveStartDate = LocalDate.of(startDate.getYear() - 1, 1, 1);
            }
        }
        return effectiveStartDate;
    }

    private static BigDecimal addOpeningBalances(Set<ChartOfAccount> chartOfAccounts, LocalDate effectiveStartDate, LocalDate effectiveEndDate, BigDecimal totalAmount) {
        // Add opening balances if within date range
        for (ChartOfAccount coa : chartOfAccounts) {
            var openingBalance = coa.getOpeningBalance();
            if (openingBalance != null && openingBalance.getDate() != null) {
                LocalDate openingDate = openingBalance.getDate();
                // Include opening balance if it's after (or equal to) start date and before (or equal to) end date
                if (!openingDate.isBefore(effectiveStartDate) && !openingDate.isAfter(effectiveEndDate)) {
                    BigDecimal openingAmount = openingBalance.getBalanceLCY() != null
                            ? openingBalance.getBalanceLCY()
                            : BigDecimal.ZERO;

                    // Check balance type - if CREDIT, negate the amount
                    if (openingBalance.getBalanceType() != null
                            && openingBalance.getBalanceType().name().equals("CREDIT")) {
                        openingAmount = openingAmount.negate();
                    }

                    totalAmount = totalAmount.add(openingAmount);
                }
            }
        }
        return totalAmount;
    }

    private BigDecimal SumTransactionItems(List<TransactionItemEntity> transactionItems, Map<String, ChartOfAccount> accountMap, BigDecimal totalAmount) {
        // Sum transaction amounts
        for (TransactionItemEntity txItem : transactionItems) {
            // Skip invalid items
            if (txItem.getStatus() != org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK) {
                continue;
            }

            BigDecimal itemAmount = getItemAmountFromItem(txItem, accountMap);

            totalAmount = totalAmount.add(itemAmount);
        }
        return totalAmount;
    }

    private BigDecimal getItemAmountFromItem(TransactionItemEntity txItem, Map<String, ChartOfAccount> accountMap) {
        BigDecimal itemAmount = BigDecimal.ZERO;

        // Check if account is on debit side
        if (accountMap.containsKey(txItem.getAccountDebit().orElse(Account.builder().code("").build()).getCode())) {
            if (txItem.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                itemAmount = itemAmount.add(txItem.getAmountLcy());
            } else {
                itemAmount = itemAmount.add(txItem.getAmountLcy().negate());
            }
        }

        // Check if account is on credit side
        if (accountMap.containsKey(txItem.getAccountCredit().orElse(Account.builder().code("").build()).getCode())) {
            if (txItem.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                itemAmount = itemAmount.subtract(txItem.getAmountLcy());
            } else {
                itemAmount = itemAmount.subtract(txItem.getAmountLcy().negate());
            }
        }
        return itemAmount;
    }

    private LocalDate getReportStartDate(IntervalType intervalType, short period, short year) {
        return switch (intervalType) {
            case MONTH -> LocalDate.of(year, period, 1);
            case QUARTER -> {
                int month = (period - 1) * 3 + 1; // Convert quarter to starting month
                yield LocalDate.of(year, month, 1);
            }
            case YEAR -> LocalDate.of(year, 1, 1);
        };
    }

    private LocalDate getReportEndDate(IntervalType intervalType, LocalDate startDate) {
        return switch (intervalType) {
            case MONTH -> startDate.plusMonths(1).minusDays(1);
            case QUARTER -> startDate.plusMonths(3).minusDays(1);
            case YEAR -> startDate.plusYears(1).minusDays(1);
        };
    }

    public Either<Problem, ReportResponseDto> publish(ReportPublishRequest request) {
        Optional<ReportEntity> reportO = reportRepository.findByOrganisationIdAndId(request.getOrganisationId(), request.getReportId());
        if (reportO.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(request.getReportId()))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }
        ReportEntity report = reportO.get();
        if (!report.isReadyToPublish()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_READY_TO_PUBLISH)
                    .withDetail("Report with ID %s is not ready to be published".formatted(request.getReportId()))
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_ALREADY_PUBLISHED)
                    .withDetail("Report with ID %s has already been published".formatted(request.getReportId()))
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        report.setLedgerDispatchApproved(true);
        report.setLedgerDispatchDate(LocalDateTime.now());
        report.setPublishedBy(authenticationUserService.getCurrentUser());
        report = reportRepository.save(report);

        applicationEventPublisher.publishEvent(PublishReportEvent.fromEntity(report));

        return Either.right(reportMapper.toResponseDto(report));

    }

    /**
     * Reprocesses a report to re-evaluate validation rules.
     * For SYSTEM data mode: regenerates field values and re-evaluates validation rules
     * For USER data mode: only re-evaluates validation rules, keeps existing field values
     * Updates the readyToPublish state and failedValidationRules based on current/regenerated field values.
     *
     * @param organisationId The organisation ID
     * @param reportId       The report ID to reprocess
     * @return Either a Problem if the report cannot be found or processed, or the updated ReportResponseDto
     */
    public Either<Problem, ReportResponseDto> reprocess(String organisationId, String reportId) {
        log.info("Reprocessing report: {}", reportId);

        // Find the report
        Optional<ReportEntity> reportOpt = reportRepository.findByOrganisationIdAndId(organisationId, reportId);
        if (reportOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_NOT_FOUND)
                    .withDetail(Constants.REPORT_WITH_ID_S_DOES_NOT_EXIST.formatted(reportId))
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportEntity report = reportOpt.get();

        // Check if report is already published - can't reprocess published reports
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle(Constants.REPORT_ALREADY_PUBLISHED)
                    .withDetail("Cannot reprocess report with ID %s because it has already been published".formatted(reportId))
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // If data mode is SYSTEM, regenerate field values
        if (report.getDataMode() == DataMode.SYSTEM) {
            log.info("Report {} is SYSTEM mode - regenerating field values", reportId);

            // Validate we have the required information to regenerate
            if (report.getIntervalType() == null || report.getYear() == 0) {
                return Either.left(Problem.builder()
                        .withTitle(Constants.MISSING_REQUIRED_FIELDS)
                        .withDetail("Cannot regenerate fields: intervalType and year are required")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Calculate date range
            LocalDate startDate = getReportStartDate(report.getIntervalType(), report.getPeriod(), report.getYear());
            LocalDate endDate = getReportEndDate(report.getIntervalType(), startDate);

            // Regenerate fields from template
            List<ReportFieldDto> regeneratedFields = fillFieldsFromTemplate(
                    report.getReportTemplate().getFields(),
                    startDate,
                    endDate,
                    false
            );

            // Convert DTOs to entities and update the report
            List<ReportFieldEntity> newFieldEntities = regeneratedFields.stream()
                    .map(reportMapper::toColumnEntity)
                    .toList();

            // Set report reference for all fields recursively
            ReportEntity finalReport = report;
            newFieldEntities.forEach(field -> setReportRecursively(field, finalReport));

            // Update the existing collection
            if (report.getFields() != null) {
                report.getFields().clear();
                report.getFields().addAll(newFieldEntities);
            } else {
                report.setFields(newFieldEntities);
            }

            log.info("Regenerated {} field(s) for report {}", newFieldEntities.size(), reportId);
        } else {
            log.info("Report {} is USER mode - keeping existing field values", reportId);
        }

        // Re-evaluate validation rules (with current or regenerated field values)
        List<ReportTemplateValidationRuleEntity> notPassedValidationRules = findNotPassedValidationRules(report);

        if (!notPassedValidationRules.isEmpty()) {
            report.setFailedValidationRules(notPassedValidationRules);
            report.setReadyToPublish(false);
            log.info("Report {} failed {} validation rule(s)", reportId, notPassedValidationRules.size());
        } else {
            report.setFailedValidationRules(new ArrayList<>());
            report.setReadyToPublish(true);
            log.info("Report {} passed all validation rules", reportId);
        }

        // Save the updated report
        report = reportRepository.save(report);

        return Either.right(reportMapper.toResponseDto(report));
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

    public void reprocessBasedOnStatusUpdates(Set<TxStatusUpdate> finalizedTransactions) {
        List<ReportEntity> reportsToReprocess = reportRepository.findAffectedByTxId(
                finalizedTransactions.stream()
                        .map(TxStatusUpdate::getTxId)
                        .toList()
        );

        log.info("Reprocessing {} report(s) based on finalized transactions", reportsToReprocess.size());

        for (ReportEntity report : reportsToReprocess) {
            Either<Problem, ReportResponseDto> reprocessResult = reprocess(report.getOrganisationId(), report.getId());
            if (reprocessResult.isLeft()) {
                log.error("Failed to reprocess report {}: {}", report.getId(), reprocessResult.getLeft().getDetail());
            } else {
                log.info("Successfully reprocessed report {}", report.getId());
            }
        }
    }
}
