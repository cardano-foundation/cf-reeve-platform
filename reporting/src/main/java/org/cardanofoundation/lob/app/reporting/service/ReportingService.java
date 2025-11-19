package org.cardanofoundation.lob.app.reporting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

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

    public Either<Problem, ReportResponseDto> create(ReportDto dto, boolean storeReport) {
        log.info("Creating report: {}", dto.getName());

        // Validate report template exists
        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(dto.getReportTemplateId());
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                .withTitle("Report Template Not Found")
                .withDetail("Report template with ID " + dto.getReportTemplateId() + " does not exist")
                .withStatus(Status.NOT_FOUND)
                .build());
        }

        ReportTemplateEntity template = templateOpt.get();

        // Validate organisation matches
        if (!template.getOrganisationId().equals(dto.getOrganisationId())) {
            return Either.left(Problem.builder()
                .withTitle("Organisation Mismatch")
                .withDetail("Report template belongs to a different organisation")
                .withStatus(Status.BAD_REQUEST)
                .build());
        }

        // Validate template fields exist
        Either<Problem, Void> fieldValidation = validateTemplateFields(dto.getFields(), template);
        if (fieldValidation.isLeft()) {
            return Either.left(fieldValidation.getLeft());
        }

        // Check if a report already exists for the same template, interval, year, and period
        ReportEntity existingReport = null;
        if (dto.getIntervalType() != null && dto.getYear() != null && dto.getPeriod() != null) {
            IntervalType intervalType = IntervalType.valueOf(dto.getIntervalType());
            Optional<ReportEntity> existingReportOpt = reportRepository.findLatestByTemplateAndPeriod(
                    dto.getOrganisationId(),
                    dto.getReportTemplateId(),
                    intervalType,
                    dto.getYear(),
                    dto.getPeriod()
            );

            if (existingReportOpt.isPresent()) {
                existingReport = existingReportOpt.get();

                // If the existing report is already published, create a new version
                if (existingReport.isLedgerDispatchApproved()) {
                    log.info("Existing report is published, creating new version {} -> {}",
                            existingReport.getVer(), existingReport.getVer() + 1);
                    existingReport = null; // Will create new entity with incremented version
                } else {
                    log.info("Overwriting existing unpublished report with ID: {}", existingReport.getId());
                }
            }
        }

        ReportEntity entity = reportMapper.toEntity(dto, existingReport, template);

        // Handle versioning: if overwriting a published report, increment version
        if (existingReport == null && dto.getIntervalType() != null && dto.getYear() != null && dto.getPeriod() != null) {
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
        // TODO Check if ready to publish
        entity.setReadyToPublish(true);

        if (storeReport) {
            entity = reportRepository.save(entity);
        }
        return Either.right(reportMapper.toResponseDto(entity));
    }

    private Either<Problem, Void> validateTemplateFields(List<ReportFieldDto> columns, ReportTemplateEntity template) {
        if (columns == null || columns.isEmpty()) {
            return Either.right(null);
        }

        // Get all valid field IDs from template
        Set<Long> validFieldIds = template.getColumns().stream()
            .map(ReportTemplateFieldEntity::getId)
            .collect(Collectors.toSet());

        // Collect all field IDs from report columns (including nested)
        Set<Long> reportFieldIds = new HashSet<>();
        collectFieldIds(columns, reportFieldIds);

        // Check if all report field IDs exist in template
        Set<Long> invalidFieldIds = reportFieldIds.stream()
            .filter(fieldId -> fieldId != null && !validFieldIds.contains(fieldId))
            .collect(Collectors.toSet());

        if (!invalidFieldIds.isEmpty()) {
            return Either.left(Problem.builder()
                .withTitle("Invalid Template Fields")
                .withDetail("The following field IDs do not exist in the template: " + invalidFieldIds)
                .withStatus(Status.BAD_REQUEST)
                .build());
        }

        return Either.right(null);
    }

    private void collectFieldIds(List<ReportFieldDto> columns, Set<Long> fieldIds) {
        if (columns == null) {
            return;
        }

        for (ReportFieldDto column : columns) {
            if (column.getTemplateFieldId() != null) {
                fieldIds.add(column.getTemplateFieldId());
            }
            if (column.getChildFields() != null) {
                collectFieldIds(column.getChildFields(), fieldIds);
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<ReportResponseDto> findById(Long id) {
        return reportRepository.findById(id)
            .map(reportMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public Optional<ReportResponseDto> findByOrganisationIdAndId(String organisationId, Long id) {
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
    public List<ReportResponseDto> findByReportTemplateId(Long reportTemplateId) {
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

    public Either<Problem, Void> delete(Long id) {
        log.info("Deleting report id: {}", id);

        Optional<ReportEntity> reportOpt = reportRepository.findById(id);
        if (reportOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Not Found")
                    .withDetail("Report with ID " + id + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportEntity report = reportOpt.get();

        // Check if report is already published
        if (report.isLedgerDispatchApproved()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Already Published")
                    .withDetail("Cannot delete report with ID " + id + " because it has already been published")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        reportRepository.deleteById(id);
        return Either.right(null);
    }

    public Either<Problem, ReportResponseDto> generate(ReportGenerateRequest request) {
        log.info("Generating report for template: {}, org: {}, interval: {}, year: {}, period: {}",
                request.getReportTemplateId(), request.getOrganisationId(),
                request.getIntervalType(), request.getYear(), request.getPeriod());

        // Validate report template exists
        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(request.getReportTemplateId());
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Template Not Found")
                    .withDetail("Report template with ID " + request.getReportTemplateId() + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReportTemplateEntity template = templateOpt.get();

        // Validate organisation matches
        if (!template.getOrganisationId().equals(request.getOrganisationId())) {
            return Either.left(Problem.builder()
                    .withTitle("Organisation Mismatch")
                    .withDetail("Report template belongs to a different organisation")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        // Calculate date range based on interval type
        IntervalType intervalType = IntervalType.valueOf(request.getIntervalType());
        Short periodValue = request.getPeriod();
        short period = (periodValue != null) ? periodValue.shortValue() : (short) 1;
        LocalDate startDate = getReportStartDate(intervalType, period, request.getYear());
        LocalDate endDate = getReportEndDate(intervalType, startDate);

        // Generate report name
        String reportName = String.format("%s - %s %d%s",
                template.getName(),
                request.getIntervalType(),
                request.getYear(),
                request.getPeriod() != null ? " Period " + request.getPeriod() : "");

        // Create report DTO with template structure and filled values
        ReportDto reportDto = ReportDto.builder()
                .organisationId(request.getOrganisationId())
                .reportTemplateId(request.getReportTemplateId())
                .name(reportName)
                .intervalType(request.getIntervalType())
                .year(request.getYear())
                .period(request.getPeriod())
                .fields(fillFieldsFromTemplate(template.getColumns(), startDate, endDate))
                .build();

        // Create the report - this will handle versioning and overwriting logic
        return create(reportDto, true);
    }

    private List<ReportFieldDto> fillFieldsFromTemplate(List<ReportTemplateFieldEntity> templateFields, LocalDate startDate, LocalDate endDate) {
        if (templateFields == null) {
            return null;
        }

        return templateFields.stream()
                .filter(field -> field.getParentField() == null) // Only top-level fields
                .map(field -> fillTemplateFieldRecursively(field, startDate, endDate))
                .collect(Collectors.toList());
    }

    private ReportFieldDto fillTemplateFieldRecursively(ReportTemplateFieldEntity templateField, LocalDate startDate, LocalDate endDate) {
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
            case MONTHLY -> LocalDate.of(year, period, 1);
            case QUARTERLY -> {
                int month = (period - 1) * 3 + 1; // Convert quarter to starting month
                yield LocalDate.of(year, month, 1);
            }
            case YEARLY -> LocalDate.of(year, 1, 1);
        };
    }

    private LocalDate getReportEndDate(IntervalType intervalType, LocalDate startDate) {
        return switch (intervalType) {
            case MONTHLY -> startDate.plusMonths(1).minusDays(1);
            case QUARTERLY -> startDate.plusMonths(3).minusDays(1);
            case YEARLY -> startDate.plusYears(1).minusDays(1);
        };
    }
}
