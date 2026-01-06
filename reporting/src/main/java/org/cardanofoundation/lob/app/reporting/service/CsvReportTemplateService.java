package org.cardanofoundation.lob.app.reporting.service;


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
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountType;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountTypeRepository;
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
    private final ChartOfAccountTypeRepository chartOfAccountTypeRepository;
    private final Validator validator;

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
            return Either.left(validationResult.getLeft().getFirst());
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
            try {
                DataMode.valueOf(firstLine.getDataMode());
            } catch (IllegalArgumentException e) {
                Problem problem = Problem.builder()
                        .withTitle(Constants.CSV_PARSING_ERROR)
                        .withDetail("Invalid data mode: " + firstLine.getDataMode() + ". Options are: SYSTEM, USER")
                        .withStatus(Status.BAD_REQUEST)
                        .build();
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
                    if (childWithSameName.isPresent()) {
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
        fieldEntity.setMappingSubTypeIds(mappedSubTypes.stream().map(ChartOfAccountSubType::getId).toList());
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
        if (!problems.isEmpty()) {
            return Either.left(problems);
        }
        return Either.right(null);
    }
}
