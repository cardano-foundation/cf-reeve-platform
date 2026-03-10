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
import org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.model.enums.DataMode;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportFieldDateRange;
import org.cardanofoundation.lob.app.reporting.model.enums.ReportTemplateType;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class CsvReportTemplateService {

    private final OrganisationPublicApiIF organisationPublicApiIF;
    private final CsvParser<TemplateCsvLine> csvParser;
    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportingRepository reportingRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final Validator validator;

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
        if (templateCsvLines.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "CSV file has no content lines.");
            problem.setTitle(Constants.CSV_PARSING_ERROR);
            return Either.left(problem);
        }
        Either<List<ProblemDetail>, Void> validationResult = validateTemplateCsvLines(templateCsvLines);
        if (validationResult.isLeft()) {
            return Either.left(validationResult.getLeft().getFirst());
        }
        List<Either<ProblemDetail, ReportTemplateDto>> results = new ArrayList<>();
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
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid report template type: " + firstLine.getReportType() + ". Options are: " + String.join(", ", Arrays.stream(ReportTemplateType.values()).map(Enum::name).toList()));
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                results.add(Either.left(problem));
                continue;
            }
            try {
                DataMode.valueOf(firstLine.getDataMode());
            } catch (IllegalArgumentException e) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid data mode: " + firstLine.getDataMode() + ". Options are: SYSTEM, USER");
                problem.setTitle(Constants.CSV_PARSING_ERROR);
                results.add(Either.left(problem));
                continue;
            }
            ReportTemplateDto reportTemplateDto = new ReportTemplateDto();
            reportTemplateDto.setOrganisationId(csvTemplateRequest.getOrganisationId());
            reportTemplateDto.setName(firstLine.getName());
            reportTemplateDto.setReportTemplateType(reportTemplateType.name());
            reportTemplateDto.setDataMode(firstLine.getDataMode());
            reportTemplateDto.setVer(1L);
            List<ReportTemplateFieldDto> fieldDtos = new ArrayList<>();
            for (TemplateCsvLine templateCsvLine : filteredLines) {
                Either<ProblemDetail, ReportTemplateFieldDto> fieldEntityResult = csvLineToTemplateField(csvTemplateRequest.getOrganisationId(), templateCsvLine);
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
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parent field not found: " + templateCsvLine.getParent() + " for field: " + templateCsvLine.getFieldName() + ". Note: The parent field must be defined before the child field in the CSV.");
                        problem.setTitle(Constants.CSV_PARSING_ERROR);
                        results.add(Either.left(problem));
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
                    Optional<ReportTemplateEntity> existingTemplateO = reportTemplateRepository
                            .findByOrgnisationIdAndNameAndReportTemplateTypeLatestVersion(dto.getOrganisationId(), dto.getName(), ReportTemplateType.valueOf(dto.getReportTemplateType()));
                    ReportTemplateEntity entity = existingTemplateO.map(existingTemplate -> {
                                List<ReportEntity> byReportTemplateId = reportingRepository.findByReportTemplateId(existingTemplate.getId());
                                ReportTemplateEntity newEntity;
                                if(byReportTemplateId.isEmpty()) {
                                    newEntity = reportTemplateMapper.toEntity(dto, existingTemplate);
                                } else {
                                    newEntity = reportTemplateMapper.toEntity(dto, ReportTemplateEntity.builder().ver(existingTemplate.getVer() + 1).build());
                                }
                                return newEntity;
                            })
                            .orElseGet(() -> reportTemplateMapper.toEntity(dto, null));
                    entity = reportTemplateRepository.saveAndFlush(entity);
                    return reportTemplateMapper.toResponseDto(entity);
                }
        )).toList());
    }

    private Either<ProblemDetail, ReportTemplateFieldDto> csvLineToTemplateField(String organisationId, TemplateCsvLine templateCsvLine) {
        ReportTemplateFieldDto fieldEntity = new ReportTemplateFieldDto();
        fieldEntity.setFieldName(templateCsvLine.getFieldName());
        ReportFieldDateRange dateRange;
        try {
            dateRange = ReportFieldDateRange.valueOf(templateCsvLine.getDateRange());
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid date range: " + templateCsvLine.getDateRange() + ". Options are: " + String.join(", ", Arrays.stream(ReportFieldDateRange.values()).map(Enum::name).toList()));
            problem.setTitle(Constants.CSV_PARSING_ERROR);
            return Either.left(problem);
        }
        fieldEntity.setDateRange(dateRange);
        fieldEntity.setNegated(templateCsvLine.getNegated());
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

    private Either<List<ProblemDetail>, Void> validateTemplateCsvLines(List<TemplateCsvLine> reportCsvLines) {
        List<ProblemDetail> problems = new ArrayList<>();
        for (TemplateCsvLine templateCsvLine : reportCsvLines) {
            Errors validateObject = validator.validateObject(templateCsvLine);
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
}
