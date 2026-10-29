package org.cardanofoundation.lob.app.reporting.service;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccount;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvTemplateRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.dto.TemplateCsvLine;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.FieldSign;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;
import org.cardanofoundation.lob.app.reporting.typeValidations.ReportTemplateTypeValidator;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CsvReportTemplateService {

    private static final String OPTIONS_ARE = ". Options are: ";
    private static final String ROW_NUMBER = "rowNumber";

    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final CsvParser<TemplateCsvLine> csvParser;
    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportingRepository reportingRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final Validator validator;
    private final ReportTemplateService reportTemplateService;
    private final ReportTemplateTypeValidator reportTemplateTypeValidator;

    public Either<ProblemDetail, List<ReportTemplateResponseDto>> createCsvTemplates(@Valid CreateCsvTemplateRequest csvTemplateRequest) {
        Optional<Organisation> organisationO = organisationPublicApiIF.findByOrganisationId(csvTemplateRequest.getOrganisationId());
        if (organisationO.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Organisation with id " + csvTemplateRequest.getOrganisationId() + " not found.");
            problem.setTitle(Constants.ORGANISATION_NOT_FOUND);
            return Either.left(problem);
        }
        Either<ProblemDetail, List<TemplateCsvLine>> parsedLines = csvParser.parseCsv(
                csvTemplateRequest.getFile(), TemplateCsvLine.class);
        if (parsedLines.isLeft()) {
            return Either.left(parsedLines.getLeft());
        }
        List<TemplateCsvLine> templateCsvLines = new ArrayList<>(parsedLines.get());
        // 1-based data row number of each line (header excluded), same convention as the funding bulk import.
        // Identity-keyed because TemplateCsvLine has no equals/hashCode and identical rows must keep their own number.
        Map<TemplateCsvLine, Integer> rowNumbers = new IdentityHashMap<>();
        for (int i = 0; i < templateCsvLines.size(); i++) {
            rowNumbers.put(templateCsvLines.get(i), i + 1);
        }
        if (templateCsvLines.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV file has no content lines.");
            problem.setTitle(Constants.CSV_PARSING_ERROR);
            return Either.left(problem);
        }
        Either<List<ProblemDetail>, Void> validationResult = validateTemplateCsvLines(templateCsvLines, rowNumbers);
        if (validationResult.isLeft()) {
            return Either.left(validationResult.getLeft().getFirst());
        }
        List<Either<ProblemDetail, ParsedTemplate>> results = new ArrayList<>();
        outerLoop:
        while (!templateCsvLines.isEmpty()) {
            TemplateCsvLine firstLine = templateCsvLines.getFirst();
            int firstRowNumber = rowNumbers.get(firstLine);
            List<TemplateCsvLine> filteredLines = templateCsvLines.stream()
                    .filter(line -> line.getName().equals(firstLine.getName()) && line.getReportType().equals(firstLine.getReportType()))
                    .toList();
            templateCsvLines.removeAll(filteredLines);
            Optional<ReportTemplateType> reportTemplateTypeO = ReportTemplateType.fromCsvLabel(firstLine.getReportType());
            if (reportTemplateTypeO.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid report type: " + firstLine.getReportType() + OPTIONS_ARE + String.join(", ", Arrays.stream(ReportTemplateType.values()).map(ReportTemplateType::getCsvLabel).toList()));
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                results.add(Either.left(atRow(problem, firstRowNumber)));
                continue;
            }
            ReportTemplateType reportTemplateType = reportTemplateTypeO.get();
            Optional<DataMode> dataModeO = DataMode.fromCsvLabel(firstLine.getDataMode());
            if (dataModeO.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid generation method: " + firstLine.getDataMode() + OPTIONS_ARE + String.join(", ", Arrays.stream(DataMode.values()).map(DataMode::getCsvLabel).toList()));
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                results.add(Either.left(atRow(problem, firstRowNumber)));
                continue;
            }
            DataMode dataMode = dataModeO.get();
            boolean active;
            if ("true".equalsIgnoreCase(firstLine.getActive())) {
                active = true;
            } else if ("false".equalsIgnoreCase(firstLine.getActive())) {
                active = false;
            } else {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid Active value: " + firstLine.getActive() + ". Options are: true, false");
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                results.add(Either.left(atRow(problem, firstRowNumber)));
                continue;
            }
            ReportTemplateDto reportTemplateDto = new ReportTemplateDto();
            reportTemplateDto.setOrganisationId(csvTemplateRequest.getOrganisationId());
            reportTemplateDto.setName(firstLine.getName());
            reportTemplateDto.setReportTemplateType(reportTemplateType.name());
            reportTemplateDto.setDataMode(dataMode.name());
            reportTemplateDto.setAccountingRegime(firstLine.getAccountingRegime());
            reportTemplateDto.setActive(active);
            reportTemplateDto.setDescription(firstLine.getDescription());
            reportTemplateDto.setVer(1L);
            List<ReportTemplateFieldDto> fieldDtos = new ArrayList<>();
            // Every field defined so far at any depth, in CSV order, so a row can reference any earlier row as its parent
            List<ReportTemplateFieldDto> definedFields = new ArrayList<>();
            for (TemplateCsvLine templateCsvLine : filteredLines) {
                int rowNumber = rowNumbers.get(templateCsvLine);
                Either<ProblemDetail, ReportTemplateFieldDto> fieldEntityResult = csvLineToTemplateField(csvTemplateRequest.getOrganisationId(), reportTemplateType, dataMode, templateCsvLine);
                if (fieldEntityResult.isLeft()) {
                    results.add(Either.left(atRow(fieldEntityResult.getLeft(), rowNumber)));
                    break outerLoop;
                }
                ReportTemplateFieldDto fieldDto = fieldEntityResult.get();
                if (!templateCsvLine.getParent().isEmpty()) {
                    // Field names are only unique within a parent, so the same name may exist under different parents; the closest preceding row wins
                    Optional<ReportTemplateFieldDto> parentFieldO = definedFields.reversed().stream()
                            .filter(fe -> fe.getFieldName().equals(templateCsvLine.getParent()))
                            .findFirst();
                    if (parentFieldO.isEmpty()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parent field not found: " + templateCsvLine.getParent() + " for field: " + templateCsvLine.getFieldName() + ". The Parent value must exactly match the Field Name of a row that appears earlier in the CSV for the same template.");
                        problem.setTitle(Constants.CSV_PARSING_ERROR);
                        results.add(Either.left(atRow(problem, rowNumber)));
                        break outerLoop;
                    }
                    ReportTemplateFieldDto parentField = parentFieldO.get();
                    if (parentField.getChildFields() == null) {
                        parentField.setChildFields(new ArrayList<>());
                    }
                    Optional<ReportTemplateFieldDto> childWithSameName = parentField.getChildFields().stream().filter(field -> field.getFieldName().equals(fieldDto.getFieldName())).findFirst();
                    if (childWithSameName.isPresent()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Duplicate field name under the same parent: " + fieldDto.getFieldName() + " under parent: " + parentField.getFieldName());
                        problem.setTitle(Constants.CSV_PARSING_ERROR);
                        results.add(Either.left(atRow(problem, rowNumber)));
                        break outerLoop;
                    }
                    parentField.getChildFields().add(fieldDto);
                } else {
                    fieldDtos.add(fieldDto);
                }
                definedFields.add(fieldDto);
            }
            reportTemplateDto.setFields(fieldDtos);
            Either<ProblemDetail, Void> dataModeValidation = reportTemplateService.validateDataMode(reportTemplateDto);
            if (dataModeValidation.isLeft()) {
                results.add(Either.left(atRow(dataModeValidation.getLeft(), firstRowNumber)));
                continue;
            }
            results.add(Either.right(new ParsedTemplate(firstRowNumber, reportTemplateDto)));
        }
        return Either.right(results.stream().map(e -> e.fold(
                left -> ReportTemplateResponseDto.builder().error(Optional.of(left)).build(),
                this::saveOrUpdateFromCsv
        )).toList());
    }

    private ReportTemplateResponseDto saveOrUpdateFromCsv(ParsedTemplate parsedTemplate) {
        ReportTemplateDto dto = parsedTemplate.dto();
        Optional<ReportTemplateEntity> existingTemplateO = reportTemplateRepository
                .findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion(dto.getOrganisationId(), dto.getName(), ReportTemplateType.valueOf(dto.getReportTemplateType()));

        // Same rules as the template API, checked here because they depend on the template being created or updated
        Either<ProblemDetail, Void> accountingRegime = reportTemplateService.validateAccountingRegime(dto, existingTemplateO);
        if (accountingRegime.isLeft()) {
            return ReportTemplateResponseDto.builder().error(Optional.of(atRow(accountingRegime.getLeft(), parsedTemplate.rowNumber()))).build();
        }

        boolean updateInPlace = existingTemplateO
                .map(existingTemplate -> reportingRepository.findByReportTemplateId(existingTemplate.getId()).isEmpty())
                .orElse(false);
        // Same report type rules as the template API. An in-place update merges into the managed template, so it is checked
        // on a detached copy first: a rejected template must not be flushed with the rest of the import
        ReportTemplateEntity entity = updateInPlace ? reportTemplateMapper.toEntity(dto, null)
                : existingTemplateO
                        .map(existingTemplate -> reportTemplateMapper.toEntity(dto, ReportTemplateEntity.builder().ver(existingTemplate.getVer() + 1).build()))
                        .orElseGet(() -> reportTemplateMapper.toEntity(dto, null));
        Either<ProblemDetail, Void> reportTypeValidated = reportTemplateTypeValidator.validateReportTemplateType(entity);
        if (reportTypeValidated.isLeft()) {
            return ReportTemplateResponseDto.builder().error(Optional.of(atRow(reportTypeValidated.getLeft(), parsedTemplate.rowNumber()))).build();
        }
        if (updateInPlace) {
            entity = reportTemplateMapper.toEntity(dto, existingTemplateO.get());
        }
        entity = reportTemplateRepository.saveAndFlush(entity);
        return reportTemplateMapper.toResponseDto(entity);
    }

    private Either<ProblemDetail, ReportTemplateFieldDto> csvLineToTemplateField(String organisationId, ReportTemplateType reportTemplateType, DataMode dataMode, TemplateCsvLine templateCsvLine) {
        ReportTemplateFieldDto fieldEntity = new ReportTemplateFieldDto();
        fieldEntity.setFieldName(templateCsvLine.getFieldName());
        // Date Range and Sign only drive Automatic generation, so a Manual template ignores them, as the template builder does.
        // The builder still stores its per-type default Date Range, which the report type validation on later edits relies on.
        fieldEntity.setDateRange(defaultDateRange(reportTemplateType));
        if (dataMode == DataMode.SYSTEM) {
            if (isBlank(templateCsvLine.getDateRange())) {
                return Either.left(csvError("Date Range is required for " + DataMode.SYSTEM.getCsvLabel() + " templates" + OPTIONS_ARE + dateRangeOptions()));
            }
            Optional<ReportFieldDateRange> dateRangeO = ReportFieldDateRange.fromCsvLabel(templateCsvLine.getDateRange());
            if (dateRangeO.isEmpty()) {
                return Either.left(csvError("Invalid date range: " + templateCsvLine.getDateRange() + OPTIONS_ARE + dateRangeOptions()));
            }
            fieldEntity.setDateRange(dateRangeO.get());
            if (isBlank(templateCsvLine.getSign())) {
                return Either.left(csvError("Sign is required for " + DataMode.SYSTEM.getCsvLabel() + " templates" + OPTIONS_ARE + signOptions()));
            }
            Optional<FieldSign> signO = FieldSign.fromCsvLabel(templateCsvLine.getSign());
            if (signO.isEmpty()) {
                return Either.left(csvError("Invalid Sign value: " + templateCsvLine.getSign() + OPTIONS_ARE + signOptions()));
            }
            fieldEntity.setNegated(signO.get().isNegated());
        }
        String[] mappedAccounts = templateCsvLine.getAccounts().split(";");
        Set<ChartOfAccount> mappendAccountTypes = new HashSet<>();
        for (String mappedAccount : mappedAccounts) {
            if (mappedAccount.isBlank()) {
                continue;
            }
            Optional<ChartOfAccount> chartOfAccountO = chartOfAccountRepository.findById(new ChartOfAccount.Id(organisationId, mappedAccount));
            if (chartOfAccountO.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Chart of account not found: " + mappedAccount);
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                return Either.left(problem);
            }
            mappendAccountTypes.add(chartOfAccountO.get());
        }
        fieldEntity.setAccounts(mappendAccountTypes.stream().map(coa -> coa.getId().getCustomerCode()).collect(Collectors.toSet()));
        return Either.right(fieldEntity);
    }

    private static ReportFieldDateRange defaultDateRange(ReportTemplateType reportTemplateType) {
        return reportTemplateType == ReportTemplateType.INCOME_STATEMENT
                ? ReportFieldDateRange.ACCUMULATED_YEAR_TO_PERIOD_END
                : ReportFieldDateRange.ACCUMULATED_START_TO_PERIOD_END;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String dateRangeOptions() {
        return String.join(", ", Arrays.stream(ReportFieldDateRange.values()).map(ReportFieldDateRange::getCsvLabel).toList());
    }

    private static String signOptions() {
        return String.join(", ", Arrays.stream(FieldSign.values()).map(FieldSign::getCsvLabel).toList());
    }

    private static ProblemDetail csvError(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(Constants.CSV_PARSING_ERROR);
        return problem;
    }

    private Either<List<ProblemDetail>, Void> validateTemplateCsvLines(List<TemplateCsvLine> reportCsvLines, Map<TemplateCsvLine, Integer> rowNumbers) {
        List<ProblemDetail> problems = new ArrayList<>();
        for (TemplateCsvLine templateCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(templateCsvLine);
            List<ObjectError> allErrors = validateObject.getAllErrors();
            if (!allErrors.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                error.setTitle(Constants.CSV_PARSING_ERROR);
                problems.add(atRow(error, rowNumbers.get(templateCsvLine)));
            }
        }
        if (!problems.isEmpty()) {
            return Either.left(problems);
        }
        return Either.right(null);
    }

    /**
     * Points a CSV error at the row that caused it: prefixes the detail with "Row N: " so it is readable as-is,
     * and exposes the number as a {@code rowNumber} property for clients that want to highlight the row.
     */
    private static ProblemDetail atRow(ProblemDetail problem, int rowNumber) {
        problem.setDetail("Row %d: %s".formatted(rowNumber, problem.getDetail()));
        problem.setProperty(ROW_NUMBER, rowNumber);
        return problem;
    }

    /** A template built from the CSV, with the data row of its first line, so save-time errors can point back to it. */
    private record ParsedTemplate(int rowNumber, ReportTemplateDto dto) {
    }
}
