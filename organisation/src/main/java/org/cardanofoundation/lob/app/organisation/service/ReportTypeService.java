package org.cardanofoundation.lob.app.organisation.service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.csv.ReportTypeFieldUpdateCsv;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.domain.request.ReportTypeFieldUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReportTypeView;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeFieldRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTypeService {

    private final ReportTypeRepository reportTypeRepository;
    private final ReportTypeFieldRepository reportTypeFieldRepository;
    private final OrganisationChartOfAccountSubTypeRepository organisationChartOfAccountSubTypeRepository;
    private final CsvParser<ReportTypeFieldUpdateCsv> csvParser;

    @Transactional(readOnly = true)
    public List<ReportTypeView> getAllReportTypes(String orgId) {
        List<ReportTypeEntity> allByOrganisationId = reportTypeRepository.findAllByOrganisationId(orgId);
        return allByOrganisationId.stream()
                .map(ReportTypeView::fromEntity)
                .toList();
    }

    @Transactional
    public Either<Problem, Void> addMappingToReportTypeField(String orgId, @Valid ReportTypeFieldUpdate reportTypeFieldUpdate) {
        Optional<ReportTypeEntity> reportTypeEntityOptional = reportTypeRepository.findByOrganisationIdAndId(orgId, reportTypeFieldUpdate.getReportTypeId());
        if (reportTypeEntityOptional.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Type not found")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        } else {
            Optional<ReportTypeFieldEntity> optionalReportField = reportTypeFieldRepository.findByReportIdAndId(reportTypeFieldUpdate.getReportTypeId(), reportTypeFieldUpdate.getReportTypeFieldId());
            if (optionalReportField.isEmpty()) {
                return Either.left(Problem.builder()
                        .withTitle("Report Type Field not found")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            ReportTypeFieldEntity reportTypeFieldEntity = optionalReportField.get();
            Optional<OrganisationChartOfAccountSubType> optionalSubType = organisationChartOfAccountSubTypeRepository.findById(String.valueOf(reportTypeFieldUpdate.getOrganisationChartOfAccountSubTypeId()));
            if (optionalSubType.isEmpty()) {
                return Either.left(Problem.builder()
                        .withTitle("Organisation Chart Of Account Sub Type not found")
                        .withStatus(Status.BAD_REQUEST)
                        .build());
            }
            List<OrganisationChartOfAccountSubType> mappingTypes = reportTypeFieldEntity.getMappingTypes();
            mappingTypes.add(optionalSubType.get());
            reportTypeFieldEntity.setMappingTypes(mappingTypes);
            reportTypeFieldRepository.saveAndFlush(reportTypeFieldEntity);
            return Either.right(null);
        }
    }

    @Transactional
    public Either<Set<Problem>, Void> addMappingToReportTypeFieldCsv(String orgId, MultipartFile file) {
        Either<Problem, List<ReportTypeFieldUpdateCsv>> csv = csvParser.parseCsv(file, ReportTypeFieldUpdateCsv.class);
        if (csv.isLeft()) {
            return Either.left(Set.of(csv.getLeft()));
        }
        List<ReportTypeFieldUpdateCsv> reportTypeFieldUpdateCsvs = csv.get();
        Set<Problem> errors = new HashSet<>();
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
                    .flatMap(s -> organisationChartOfAccountSubTypeRepository.findFirstByOrganisationIdAndName(orgId, s))
                    .ifPresent(organisationChartOfAccountSubType -> reportTypeFieldUpdate.setOrganisationChartOfAccountSubTypeId(organisationChartOfAccountSubType.getId()));

            if (reportTypeFieldUpdate.getReportTypeId() == null || reportTypeFieldUpdate.getReportTypeFieldId() == null || reportTypeFieldUpdate.getOrganisationChartOfAccountSubTypeId() == null) {
                errors.add(Problem.builder()
                        .withTitle("Can't add mappings to Report Type field: %s".formatted(reportUpdate))
                        .withStatus(Status.BAD_REQUEST)
                        .build());
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
