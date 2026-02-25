package org.cardanofoundation.lob.app.organisation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.domain.csv.ReportTypeFieldUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.ChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.domain.request.ReportTypeFieldUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReportTypeView;
import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeFieldRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTypeService {

    private final ReportTypeRepository reportTypeRepository;
    private final ReportTypeFieldRepository reportTypeFieldRepository;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    private final CsvParser<ReportTypeFieldUpdateCsv> csvParser;

    @Transactional(readOnly = true)
    public List<ReportTypeView> getAllReportTypes(String orgId) {
        List<ReportTypeEntity> allByOrganisationId = reportTypeRepository.findAllByOrganisationId(orgId);
        return allByOrganisationId.stream()
                .map(ReportTypeView::fromEntity)
                .toList();
    }

    @Transactional
    public Either<ProblemDetail, Void> addMappingToReportTypeField(String orgId, @Valid ReportTypeFieldUpdate reportTypeFieldUpdate) {
        Optional<ReportTypeEntity> reportTypeEntityOptional = reportTypeRepository.findByOrganisationIdAndId(orgId, reportTypeFieldUpdate.getReportTypeId());
        if (reportTypeEntityOptional.isEmpty()) {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Report Type not found");
            detail.setTitle("REPORT_TYPE_NOT_FOUND");
            return Either.left(detail);
        } else {
            Optional<ReportTypeFieldEntity> optionalReportField = reportTypeFieldRepository.findByReportIdAndId(reportTypeFieldUpdate.getReportTypeId(), reportTypeFieldUpdate.getReportTypeFieldId());
            if (optionalReportField.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Report Type Field not found");
                problem.setTitle("REPORT_TYPE_FIELD_NOT_FOUND");
                return Either.left(problem);
            }
            ReportTypeFieldEntity reportTypeFieldEntity = optionalReportField.get();
            Optional<ChartOfAccountSubType> optionalSubType = chartOfAccountSubTypeRepository.findById(String.valueOf(reportTypeFieldUpdate.getOrganisationChartOfAccountSubTypeId()));
            if (optionalSubType.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Organisation Chart of Account Sub type not found");
                problem.setTitle("SUB_TYPE_NOT_FOUND");
                return Either.left(problem);
            }
            List<ChartOfAccountSubType> mappingTypes = reportTypeFieldEntity.getMappingTypes();
            mappingTypes.add(optionalSubType.get());
            reportTypeFieldEntity.setMappingTypes(mappingTypes);
            reportTypeFieldRepository.saveAndFlush(reportTypeFieldEntity);
            return Either.right(null);
        }
    }

    @Transactional
    public Either<List<ProblemDetail>, Void> addMappingToReportTypeFieldCsv(String orgId, MultipartFile file) {
        Either<ProblemDetail, List<ReportTypeFieldUpdateCsv>> csv = csvParser.parseCsv(file, ReportTypeFieldUpdateCsv.class);
        if (csv.isLeft()) {
            return Either.left(List.of(csv.getLeft()));
        }
        List<ReportTypeFieldUpdateCsv> reportTypeFieldUpdateCsvs = csv.get();
        List<ProblemDetail> errors = new ArrayList<>();
        for (ReportTypeFieldUpdateCsv reportUpdate : reportTypeFieldUpdateCsvs) {
            ReportTypeFieldUpdate reportTypeFieldUpdate = new ReportTypeFieldUpdate(
                    safeParse(reportUpdate.getReportType()),
                    safeParse(reportUpdate.getReportTypeField()),
                    safeParse(reportUpdate.getSubType()));
            Optional.ofNullable(reportUpdate.getReportType())
                    .flatMap(reportTypeName -> reportTypeRepository.findByOrganisationAndReportName(orgId, reportTypeName))
                    .ifPresent(reportTypeEntity -> reportTypeFieldUpdate.setReportTypeId(reportTypeEntity.getId()));
            Optional.ofNullable(reportUpdate.getReportTypeField())
                    .flatMap(reportTypeField -> reportTypeFieldRepository.findFirstByReportIdAndName(reportTypeFieldUpdate.getReportTypeId(), reportTypeField))
                    .ifPresent(reportTypeFieldEntity -> reportTypeFieldUpdate.setReportTypeFieldId(reportTypeFieldEntity.getId()));
            Optional.ofNullable(reportUpdate.getSubType())
                    .flatMap(s -> chartOfAccountSubTypeRepository.findFirstByOrganisationIdAndName(orgId, s))
                    .ifPresent(organisationChartOfAccountSubType -> reportTypeFieldUpdate.setOrganisationChartOfAccountSubTypeId(organisationChartOfAccountSubType.getId()));

            if (reportTypeFieldUpdate.getReportTypeId() == null || reportTypeFieldUpdate.getReportTypeFieldId() == null || reportTypeFieldUpdate.getOrganisationChartOfAccountSubTypeId() == null) {
                ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Can't add mappings to Reporty Type field: %s".formatted(reportUpdate));
                problemDetail.setTitle("CAN'T_ADD_MAPPINGS_TO_REPORT_TYPE_FIELD");
                errors.add(problemDetail);
                continue;
            }
            addMappingToReportTypeField(orgId, reportTypeFieldUpdate)
                    .peekLeft(errors::add);
        }
        if (errors.isEmpty()) {
            return Either.right(null);
        } else {
            return Either.left(errors);
        }
    }

    private Long safeParse(String input) {
        try {
            return (input == null || input.isBlank()) ? null : Long.valueOf(input);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
