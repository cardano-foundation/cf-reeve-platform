package org.cardanofoundation.lob.app.reporting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.reporting.dto.CreateCsvReportRequest;
import org.cardanofoundation.lob.app.reporting.dto.ReportCsvLine;
import org.cardanofoundation.lob.app.reporting.dto.ReportDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateFieldEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CsvReportService {

    private final ReportingService reportingService;
    private final ReportTemplateRepository reportTemplateRepository;
    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final CsvParser<ReportCsvLine> csvParser;
    private final Validator validator;

    public Either<Problem, List<ReportResponseDto>> createCsvReports(@Valid CreateCsvReportRequest csvTemplateRequest) {
        Optional<Organisation> organisationO = organisationPublicApiIF.findByOrganisationId(csvTemplateRequest.getOrganisationId());
        if (organisationO.isEmpty()) {
            Problem problem = Problem.builder()
                    .withTitle(Constants.ORGANISATION_NOT_FOUND)
                    .withDetail("Organisation with id " + csvTemplateRequest.getOrganisationId() + " not found.")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
            return Either.left(problem);
        }
        Either<Problem, List<ReportCsvLine>> parsedLines = csvParser.parseCsv(csvTemplateRequest.getFile(), ReportCsvLine.class);
        if (parsedLines.isLeft()) {
            return Either.left(parsedLines.getLeft());
        }
        List<ReportCsvLine> reportLines = new ArrayList<>(parsedLines.get());
        Either<List<Problem>, Void> voids = validateReportCsvLines(reportLines);
        if (voids.isLeft()) {
            return Either.left(voids.getLeft().getFirst());
        }
        List<ReportResponseDto> createdReports = new ArrayList<>();
        while(!reportLines.isEmpty()) {
            ReportCsvLine line = reportLines.get(0);

            Optional<ReportTemplateEntity> templateEntityO = reportTemplateRepository.findLatestByOrganisationIdAndName(csvTemplateRequest.getOrganisationId(), line.getTemplateName());
            if(templateEntityO.isEmpty()) {
                createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(Problem.builder()
                                                .withTitle("REPORT_TEMPLATE_NOT_FOUND")
                                                .withDetail("Report template with name " + line.getTemplateName() + " not found.")
                                                .withStatus(Status.BAD_REQUEST)
                                        .build()
                                ))
                        .build());
                continue;
            }
            ReportTemplateEntity reportTemplateEntity = templateEntityO.get();
            boolean fieldsSetupSuccessfully = true;
            List<ReportFieldDto> fields = new ArrayList<>();
            DataMode dataMode;
            try {
                dataMode = DataMode.valueOf(line.getDataMode());
            } catch (IllegalArgumentException e) {
                createdReports.add(ReportResponseDto.builder()
                        .error(Optional.of(Problem.builder()
                                .withTitle("INVALID_DATA_MODE")
                                .withDetail("Data mode '" + line.getDataMode() + "' is not valid. Must be either GENERATED or USER.")
                                .withStatus(Status.BAD_REQUEST)
                                .build()
                        ))
                        .build());
                continue;
            }
            List<ReportCsvLine> sameReportLines = reportLines.stream()
                    .filter(l -> l.getTemplateName().equals(line.getTemplateName())
                            && l.getName().equals(line.getName())
                            && l.getIntervalType().equals(line.getIntervalType())
                            && l.getYear().equals(line.getYear())
                            && ((l.getPeriod() == null && line.getPeriod() == null) || (l.getPeriod() != null && l.getPeriod().equals(line.getPeriod())))
                    )
                    .toList();
            reportLines.removeAll(sameReportLines);
            if (dataMode == DataMode.USER) {
                for (ReportCsvLine reportCsvLine : sameReportLines) {
                    if (reportCsvLine.getField() == null || reportCsvLine.getField().isBlank()) {
                        fieldsSetupSuccessfully = false;
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(Problem.builder()
                                        .withTitle("MISSING_FIELD_NAME")
                                        .withDetail("Field name is missing in CSV line.")
                                        .withStatus(Status.BAD_REQUEST)
                                        .build()
                                ))
                                .build());
                        break;
                    }
                    BigDecimal amount;
                    try {
                        amount = new BigDecimal(reportCsvLine.getAmount());
                    } catch (NumberFormatException e) {
                        fieldsSetupSuccessfully = false;
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(Problem.builder()
                                        .withTitle("INVALID_AMOUNT_FORMAT")
                                        .withDetail("Amount '" + reportCsvLine.getAmount() + "' is not a valid number.")
                                        .withStatus(Status.BAD_REQUEST)
                                        .build()
                                ))
                                .build());
                        break;
                    }
                    String[] fieldNamesSplit = reportCsvLine.getField().split("\\."); // Since field is not null nor blank the array size must be at least 1
                    Either<Problem, Void> updateResult = updateFields(amount, new ArrayList<>(Arrays.asList(fieldNamesSplit)), fields, reportTemplateEntity.getFields(), null);
                    if (updateResult.isLeft()) {
                        fieldsSetupSuccessfully = false;
                        createdReports.add(ReportResponseDto.builder()
                                .error(Optional.of(updateResult.getLeft()))
                                .build());
                        break;
                    }
                }
                if (!fieldsSetupSuccessfully) {
                    continue;
                }
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
            createdReports.add(reportingService.create(reportDto));
        }
        return Either.right(createdReports);
    }

    private Either<Problem, Void> updateFields(BigDecimal amount, List<String> fieldNames, List<ReportFieldDto> fields, List<ReportTemplateFieldEntity> entities, ReportTemplateFieldEntity parent) {
        if(fieldNames.isEmpty()) {
            return Either.right(null);
        }
        String currentFieldName = fieldNames.removeFirst();
        Optional<ReportTemplateFieldEntity> templateField = entities.stream().filter(e -> e.getName().equals(currentFieldName)).findFirst();
        if(templateField.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("FIELD_NOT_FOUND")
                    .withDetail("Field with name " + currentFieldName + " not found in template " + (parent != null ? "for parent field: " + parent.getName() : "at root level") + ".")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
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
            return updateFields(amount, fieldNames, fieldDto.getChildFields(), templateFieldEntity.getChildFields(), templateFieldEntity);
        }
    }

    private Either<List<Problem>, Void> validateReportCsvLines(List<ReportCsvLine> reportCsvLines) {
        List<Problem> problems = new ArrayList<>();
        for (ReportCsvLine reportCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(reportCsvLine);
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
        if (!problems.isEmpty()) {
            return Either.left(problems);
        }
        return Either.right(null);
    }
}
