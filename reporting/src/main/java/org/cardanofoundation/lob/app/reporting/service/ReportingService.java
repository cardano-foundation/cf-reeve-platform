package org.cardanofoundation.lob.app.reporting.service;

import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.report.ReportMode.SYSTEM;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.TransactionItemEntity;
import org.cardanofoundation.lob.app.accounting_reporting_core.repository.TransactionItemRepository;
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
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.util.Constants;
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

    public ReportResponseDto create(ReportDto dto) {
        log.info("Creating report: {}", dto.getName());

        // Validate dataMode is provided and valid
        DataMode dataMode = validateAndParseDataMode(dto.getDataMode());
        if (dataMode == null) {
            return ReportResponseDto.builder().error(Optional.of(buildDataModeError(dto.getDataMode()))).build();
        }

        // Validate report template exists and organization matches
        Either<Problem, ReportTemplateEntity> templateResult = validateTemplate(dto.getReportTemplateId(), dto.getOrganisationId());
        if (templateResult.isLeft()) {
            return ReportResponseDto.builder().error(Optional.of(templateResult.getLeft())).build();
        }
        ReportTemplateEntity template = templateResult.get();
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

        // TODO Check if ready to publish
        entity.setReadyToPublish(true);

        entity = reportRepository.save(entity);
        return reportMapper.toResponseDto(entity);
    }

    /**
     * This function validates the time-related fields in the ReportDto.
     * It checks if intervalTypeStr is a valid Enum object and if period and year are within acceptable ranges.
     * @return Either a ReportResponseDto with error details if validation fails, or Void if validation passes.
     */
    private Either<Problem, Void> validateTimeInDto(short year, short period, String intervalTypeStr) {
        IntervalType intervalType;
        try {
            intervalType = IntervalType.valueOf(intervalTypeStr);
        } catch (IllegalArgumentException e) {
            return Either.left(
                    Problem.builder()
                            .withTitle("Invalid Interval Type")
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
        // Validating Time in DTO
        Either<Problem, Void> timeValidation = validateTimeInDto(request.getYear(), request.getPeriod(), request.getIntervalType());
        if (timeValidation.isLeft()) {
            return Either.left(timeValidation.getLeft());
        }
        // Calculate date range and generate fields
        IntervalType intervalType = IntervalType.valueOf(request.getIntervalType());
        Short periodValue = request.getPeriod();
        short period = (periodValue != null) ? periodValue.shortValue() : (short) 1;
        LocalDate startDate = getReportStartDate(intervalType, period, request.getYear());
        LocalDate endDate = getReportEndDate(intervalType, startDate);

        List<ReportFieldDto> fields = fillFieldsFromTemplate(template.getColumns(), startDate, endDate);

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
        previewEntity.setReadyToPublish(true);

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

    private Problem buildDataModeError(String dataModeStr) {
        if (dataModeStr == null || dataModeStr.isBlank()) {
            return Problem.builder()
                    .withTitle("Data Mode Required")
                    .withDetail("Data mode must be specified (GENERATED or USER)")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
        }
        return Problem.builder()
                .withTitle("Invalid Data Mode")
                .withDetail("Data mode must be either GENERATED or USER")
                .withStatus(Status.BAD_REQUEST)
                .build();
    }

    private Either<Problem, ReportTemplateEntity> validateTemplate(String templateId, String organisationId) {
        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(templateId);
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_TEMPLATE_NOT_FOUND")
                    .withDetail("Report template with ID " + templateId + " does not exist")
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
        if (dto.getIntervalType() == null || dto.getYear() == null) {
            return Either.left(Problem.builder()
                    .withTitle("Missing Required Fields")
                    .withDetail("intervalType and year are required for GENERATED reports")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        IntervalType intervalType = IntervalType.valueOf(dto.getIntervalType());
        Short periodValue = dto.getPeriod();
        short period = (periodValue != null) ? periodValue.shortValue() : (short) 1;
        LocalDate startDate = getReportStartDate(intervalType, period, dto.getYear());
        LocalDate endDate = getReportEndDate(intervalType, startDate);

        List<ReportFieldDto> fields = fillFieldsFromTemplate(template.getColumns(), startDate, endDate);
        return Either.right(fields);
    }

    private Either<Problem, List<ReportFieldDto>> validateUserFields
            (List<ReportFieldDto> fields, ReportTemplateEntity template) {
        if (fields == null || fields.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Missing Fields for User Report")
                    .withDetail("USER reports must have fields specified.")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // Validate template fields exist
        Either<Problem, Void> fieldValidation = validateTemplateFields(fields, template.getColumns());
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
     * @param reportFields The report fields to validate
     * @param templateFields The template fields to validate against
     * @param pathPrefix Path prefix for error messages (e.g., "Income.Revenue")
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
                        .withTitle("Missing Template Field ID")
                        .withDetail("Report field at path '" + pathPrefix + "' is missing template field ID")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Check if template field exists at this level
            ReportTemplateFieldEntity templateField = templateFieldMap.get(reportField.getTemplateFieldId());
            if (templateField == null) {
                return Either.left(Problem.builder()
                        .withTitle("Invalid Template Field")
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
                        .withTitle("Invalid Field Value")
                        .withDetail("Field '" + currentPath + "' has child fields and must not have a value set. " +
                                   "Only leaf fields (fields without children) can have values.")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }

            // Recursively validate child fields
            if (hasChildren) {
                List<ReportTemplateFieldEntity> templateChildFields = templateField.getChildFields();

                if (templateChildFields == null || templateChildFields.isEmpty()) {
                    return Either.left(Problem.builder()
                            .withTitle("Invalid Field Structure")
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
                    .withTitle("REPORT_NOT_FOUND")
                    .withDetail("Report with ID " + id + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportEntity report = reportOpt.get();

        // Check if report is already published
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle("REPORT_ALREADY_PUBLISHED")
                    .withDetail("Cannot delete report with ID " + id + " because it has already been published")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        reportRepository.deleteById(id);
        return Either.right(null);
    }

    private List<ReportFieldDto> fillFieldsFromTemplate
            (List<ReportTemplateFieldEntity> templateFields, LocalDate startDate, LocalDate endDate) {
        if (templateFields == null) {
            return null;
        }

        return templateFields.stream()
                .filter(field -> field.getParentField() == null) // Only top-level fields
                .map(field -> fillTemplateFieldRecursively(field, startDate, endDate))
                .collect(Collectors.toList());
    }

    private ReportFieldDto fillTemplateFieldRecursively(ReportTemplateFieldEntity templateField, LocalDate
            startDate, LocalDate endDate) {
        List<ReportFieldDto> childColumns = null;

        if (templateField.getChildFields() != null && !templateField.getChildFields().isEmpty()) {
            // Has children - recursively fill child fields
            childColumns = templateField.getChildFields().stream()
                    .map(child -> fillTemplateFieldRecursively(child, startDate, endDate))
                    .collect(Collectors.toList());
        }

        // Calculate value based on mapping types (if no children or if it's an accumulated field)
        BigDecimal value = null;
        if (templateField.getMappingTypes() != null && !templateField.getMappingTypes().isEmpty()) {
            value = calculateFieldValue(templateField, startDate, endDate);
        }

        return ReportFieldDto.builder()
                .templateFieldId(templateField.getId())
                .templateFieldName(templateField.getName())
                .value(value)
                .childFields(childColumns)
                .build();
    }

    private BigDecimal calculateFieldValue(ReportTemplateFieldEntity field, LocalDate startDate, LocalDate endDate) {
        LocalDate effectiveStartDate = startDate;
        LocalDate effectiveEndDate = endDate;

        // Adjust date range based on accumulation flags
        if (field.isAccumulatedYearly()) {
            effectiveStartDate = LocalDate.of(startDate.getYear(), 1, 1);
        }
        if (field.isAccumulated()) {
            effectiveStartDate = LocalDate.EPOCH;
        }
        if (field.isAccumulatedPreviousYear()) {
            if (!field.isAccumulated()) {
                effectiveStartDate = LocalDate.of(startDate.getYear() - 1, 1, 1);
            }
            effectiveEndDate = LocalDate.of(startDate.getYear() - 1, 12, 31);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Get all chart of accounts mapped to this field's subtypes
        if (field.getMappingTypes() != null && !field.getMappingTypes().isEmpty()) {
            List<Long> subTypeIds = field.getMappingTypes().stream()
                    .map(org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType::getId)
                    .collect(Collectors.toList());

            Set<ChartOfAccount> chartOfAccounts = chartOfAccountRepository.findAllByOrganisationIdSubTypeIds(subTypeIds);

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

            // Get account codes for transaction lookup
            List<String> accountCodes = chartOfAccounts.stream()
                    .filter(coa -> coa.getId() != null && coa.getId().getCustomerCode() != null)
                    .map(coa -> coa.getId().getCustomerCode())
                    .collect(Collectors.toList());

            if (!accountCodes.isEmpty()) {
                // Query transaction items for these accounts in the date range
                List<TransactionItemEntity> transactionItems = transactionItemRepository
                        .findTransactionItemsByAccountCodeAndDateRange(accountCodes, effectiveStartDate, effectiveEndDate);

                // Map accounts for quick lookup
                Map<String, ChartOfAccount> accountMap = chartOfAccounts.stream()
                        .filter(coa -> coa.getId() != null && coa.getId().getCustomerCode() != null)
                        .collect(Collectors.toMap(
                                coa -> coa.getId().getCustomerCode(),
                                coa -> coa
                        ));

                // Sum transaction amounts
                for (TransactionItemEntity txItem : transactionItems) {
                    // Skip invalid items
                    if (txItem.getStatus() != org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TxItemValidationStatus.OK) {
                        continue;
                    }

                    BigDecimal itemAmount = BigDecimal.ZERO;

                    // Check if account is on debit side
                    if (txItem.getAccountDebit().isPresent() && accountMap.containsKey(txItem.getAccountDebit().get().getCode())) {
                        if (txItem.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                            itemAmount = itemAmount.add(txItem.getAmountLcy());
                        } else {
                            itemAmount = itemAmount.add(txItem.getAmountLcy().negate());
                        }
                    }

                    // Check if account is on credit side
                    if (txItem.getAccountCredit().isPresent() && accountMap.containsKey(txItem.getAccountCredit().get().getCode())) {
                        if (txItem.getOperationType() == org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.OperationType.DEBIT) {
                            itemAmount = itemAmount.subtract(txItem.getAmountLcy());
                        } else {
                            itemAmount = itemAmount.subtract(txItem.getAmountLcy().negate());
                        }
                    }

                    totalAmount = totalAmount.add(itemAmount);
                }
            }
        }

        // Apply negation if configured
        if (field.isNegated()) {
            totalAmount = totalAmount.negate();
        }

        return totalAmount.stripTrailingZeros();
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
                    .withTitle("Report Not Found")
                    .withDetail("Report with ID " + request.getReportId() + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }
        ReportEntity report = reportO.get();
        if (!report.isReadyToPublish()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Not Ready to Publish")
                    .withDetail("Report with ID " + request.getReportId() + " is not ready to be published")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Already Published")
                    .withDetail("Report with ID " + request.getReportId() + " has already been published")
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
}
