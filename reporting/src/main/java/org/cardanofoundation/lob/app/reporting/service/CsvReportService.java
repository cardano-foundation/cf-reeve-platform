package org.cardanofoundation.lob.app.reporting.service;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

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

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

import org.cardanofoundation.lob.app.accounting_reporting_core.utils.Constants;
import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportCsvLine;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.IntervalType;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CsvReportService {

    private final ReportingService reportingService;
    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportingRepository reportingRepository;
    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final CsvParser<ReportCsvLine> csvParser;
    private final Validator validator;

    public Either<ProblemDetail, List<ReportResponseDto>> createCsvReports(@Valid CreateCsvReportRequest csvTemplateRequest) {
        Optional<Organisation> organisationO = organisationPublicApiIF.findByOrganisationId(csvTemplateRequest.getOrganisationId());
        if (organisationO.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Organisation with id " + csvTemplateRequest.getOrganisationId() + " not found.");
            problem.setTitle(Constants.ORGANISATION_NOT_FOUND);
            return Either.left(problem);
        }
        Either<ProblemDetail, List<ReportCsvLine>> parsedLines = csvParser.parseCsv(csvTemplateRequest.getFile(), ReportCsvLine.class);
        if (parsedLines.isLeft()) {
            return Either.left(parsedLines.getLeft());
        }
        List<ReportCsvLine> reportLines = new ArrayList<>(parsedLines.get());
        if (reportLines.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV file has no content lines.");
            problem.setTitle(Constants.CSV_PARSING_ERROR);
            return Either.left(problem);
        }
        List<ReportCsvLine> copyOfReportLines = new ArrayList<>(reportLines); // Creating a copy to keep track of original indexes for error reporting
        Either<List<ProblemDetail>, Void> voids = validateReportCsvLines(reportLines);
        if (voids.isLeft()) {
            return Either.left(voids.getLeft().getFirst());
        }
        List<ReportResponseDto> createdReports = new ArrayList<>();
        while(!reportLines.isEmpty()) {
            ReportCsvLine line = reportLines.getFirst();
            int index = copyOfReportLines.indexOf(line) + 2; // +2 to convert from 0-based to 1-based and to account for header line
            Optional<ReportTemplateEntity> templateEntityO = reportTemplateRepository.findLatestByOrganisationIdAndName(csvTemplateRequest.getOrganisationId(), line.getTemplateName());
            if(templateEntityO.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Report template with name " + line.getTemplateName() + " not found at line.");
                problem.setTitle("REPORT_TEMPLATE_NOT_FOUND");
                createdReports.add(ReportResponseDto.builder()
                        .error(Optional.of(problem))
                        .build());
                reportLines.removeFirst();
                continue;
            }
            ReportTemplateEntity reportTemplateEntity = templateEntityO.get();
            List<ReportFieldDto> fields = new ArrayList<>();
            DataMode dataMode;
            try {
                dataMode = DataMode.valueOf(line.getDataMode());
            } catch (IllegalArgumentException e) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Data mode '" + line.getDataMode() + "' is not valid. Must be either SYSTEM or USER at line.");
                problem.setTitle("INVALID_DATA_MODE");
                createdReports.add(ReportResponseDto.builder()
                        .error(Optional.of(problem))
                        .build());
                reportLines.removeFirst();
                continue;
            }
            // Filtering all lines that belong to the same report
            List<ReportCsvLine> sameReportLines = reportLines.stream()
                    .filter(l -> l.getTemplateName().equals(line.getTemplateName())
                            && l.getName().equals(line.getName())
                            && l.getIntervalType().equals(line.getIntervalType())
                            && l.getYear().equals(line.getYear())
                            && ((l.getPeriod() == null && line.getPeriod() == null) || (l.getPeriod() != null && l.getPeriod().equals(line.getPeriod())))
                    )
                    .toList();

            if (dataMode == DataMode.USER) {
                for (ReportCsvLine reportCsvLine : sameReportLines) {
                    index = copyOfReportLines.indexOf(reportCsvLine) + 2; // +2 to convert from 0-based to 1-based and to account for header line
                    reportLines.remove(reportCsvLine);
                    if (reportCsvLine.getField() == null || reportCsvLine.getField().isBlank()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Field name is missing in CSV line at line.");
                        problem.setTitle("MISSING_FIELD_NAME");
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(problem))
                                .build());
                        continue;
                    }
                    BigDecimal amount;
                    try {
                        amount = new BigDecimal(Optional.ofNullable(reportCsvLine.getAmount()).orElse("0"));
                    } catch (NumberFormatException e) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Amount '" + reportCsvLine.getAmount() + "' is not a valid number at.");
                        problem.setTitle("INVALID_AMOUNT_FORMAT");
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(problem))
                                .build());
                        continue;
                    }
                    String[] fieldNamesSplit = reportCsvLine.getField().split("\\."); // Since field is not null nor blank the array size must be at least 1
                    Either<ProblemDetail, Void> updateResult = updateFields(amount, new ArrayList<>(Arrays.asList(fieldNamesSplit)), fields, reportTemplateEntity.getFields(), null, reportTemplateEntity, index);
                    if (updateResult.isLeft()) {
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(updateResult.getLeft()))
                                .build());
                        continue;
                    }
                }
            }
            if(sameReportLines.size() > 1 && dataMode == DataMode.SYSTEM) {
                // Multiple lines for the same report in SYSTEM mode is not allowed
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Multiple lines for the same report are not allowed in SYSTEM data mode.");
                problem.setTitle("MULTIPLE_LINES_FOR_SYSTEM_REPORT");
                createdReports.add(ReportResponseDto.builder()
                        .error(Optional.of(problem))
                        .build());
                // Removing all lines for this report to prevent re-processing
                reportLines.removeAll(sameReportLines);
                continue;
            }
            if(fields.isEmpty() && dataMode == DataMode.USER) {
                // All lines for this report had errors, skipping report creation
                continue;
            }
            ReportDto reportDto = ReportDto.builder()
                    .reportTemplateId(reportTemplateEntity.getId())
                    .name(line.getName())
                    .intervalType(line.getIntervalType())
                    .period(line.getPeriod())
                    .year(line.getYear())
                    .dataMode(line.getDataMode())
                    .fields(fields)
                    .build();
            reportDto.setOrganisationId(csvTemplateRequest.getOrganisationId());
            ReportResponseDto reportResponseDto = reportingService.create(reportDto);
            // Removing all lines for this report to prevent re-processing
            reportLines.removeAll(sameReportLines);
            if(reportResponseDto.getError().isPresent()) {
                ProblemDetail problem = reportResponseDto.getError().get();
                // Create a new ProblemDetail with the line number added to the detail
                ProblemDetail enhancedProblem = ProblemDetail.forStatusAndDetail(
                        HttpStatus.valueOf(problem.getStatus()),
                        "Line " + index + ": " + problem.getDetail());
                enhancedProblem.setTitle(problem.getTitle());
                reportResponseDto.setError(Optional.of(enhancedProblem));
                createdReports.add(reportResponseDto);
            } else {
                createdReports.add(reportResponseDto);
            }
        }
        return Either.right(createdReports);
    }

    private Either<ProblemDetail, Void> updateFields(BigDecimal amount, List<String> fieldNames, List<ReportFieldDto> fields, List<ReportTemplateFieldEntity> entities, ReportTemplateFieldEntity parent, ReportTemplateEntity reportTemplateEntity, int index) {
        if(fieldNames.isEmpty()) {
            return Either.right(null);
        }
        String currentFieldName = fieldNames.removeFirst();
        Optional<ReportTemplateFieldEntity> templateField = entities.stream().filter(e -> e.getName().equals(currentFieldName)).findFirst();
        if(templateField.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Line " + index + ": Field with name " + currentFieldName + " not found in template " + reportTemplateEntity.getName() + " " + (parent != null ? "for parent field: " + parent.getName() : "at root level") + ".");
            problem.setTitle("FIELD_NOT_FOUND");
            return Either.left(problem);
        }
        ReportTemplateFieldEntity templateFieldEntity = templateField.get();
        Optional<ReportFieldDto> existingFieldDtoO = fields.stream().filter(f -> f.getTemplateFieldId().equals(templateFieldEntity.getId())).findFirst();
        ReportFieldDto fieldDto = existingFieldDtoO.orElseGet(() -> {
            ReportFieldDto newReportFieldDto = ReportFieldDto.builder()
                    .templateFieldId(templateFieldEntity.getId())
                    .templateFieldName(templateFieldEntity.getName())
                    .build();
            fields.add(newReportFieldDto);
            return newReportFieldDto;
        });
        if(fieldNames.isEmpty()) {
            fieldDto.setValue(amount);
            return Either.right(null);
        } else {
            if (fieldDto.getChildFields() == null) {
                fieldDto.setChildFields(new ArrayList<>());
            }
            return updateFields(amount, fieldNames, fieldDto.getChildFields(), templateFieldEntity.getChildFields(), templateFieldEntity, reportTemplateEntity, index);
        }
    }

    private Either<List<ProblemDetail>, Void> validateReportCsvLines(List<ReportCsvLine> reportCsvLines) {
        List<ProblemDetail> problems = new ArrayList<>();
        for (ReportCsvLine reportCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(reportCsvLine);
            List<ObjectError> allErrors = validateObject.getAllErrors();
            if (!allErrors.isEmpty()) {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                error.setTitle(Constants.CSV_PARSING_ERROR);
                problems.add(error);
            }
        }
        if (!problems.isEmpty()) {
            return Either.left(problems);
        }
        return Either.right(null);
    }

    public void downloadReportAsCsv(String organisationId,
                                    List<Short> years,
                                    List<IntervalType> intervalTypes,
                                    List<Short> periods,
                                    LedgerDispatchStatus ledgerStatus,
                                    List<ReportTemplateType> reportTypes,
                                    List<String> reportTemplateIds,
                                    String txHash,
                                    Boolean isReadyToPublish,
                                    Boolean ledgerDispatchApproved, OutputStream outputStream) {
        Page<ReportEntity> allReports = reportingRepository.findAll(organisationId, years, intervalTypes, periods, ledgerStatus, reportTypes, reportTemplateIds, txHash, isReadyToPublish, ledgerDispatchApproved, Pageable.unpaged());
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Template name","Name","Interval type","Period","Year","Data mode","Field name","Amount"};
            csvWriter.writeNext(header, false);
            for(ReportEntity reportEntity : allReports) {
                reportEntity.getFields().forEach(field -> writeLeafFieldsToCsv(csvWriter, reportEntity, field, ""));
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Failed to download reports", e);
        }
    }

    private void writeLeafFieldsToCsv(CSVWriter csvWriter, ReportEntity reportEntity, ReportFieldEntity field, String prefix) {
        if(field.getChildFields().isEmpty()) {
            String[] data = {
                    reportEntity.getReportTemplate().getName(),
                    reportEntity.getName(),
                    reportEntity.getIntervalType().name(),
                    reportEntity.getPeriod() + "",
                    reportEntity.getYear() + "",
                    reportEntity.getDataMode().name(),
                    prefix + field.getFieldTemplate().getName(),
                    field.getValue() != null ? field.getValue().toString() : ""
            };
            csvWriter.writeNext(data, false);
        } else {
            String newPrefix = prefix + field.getFieldTemplate().getName() + ".";
            field.getChildFields().forEach(childField -> writeLeafFieldsToCsv(csvWriter, reportEntity, childField, newPrefix));
        }
    }
}
