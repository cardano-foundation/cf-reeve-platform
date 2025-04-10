package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationChartOfAccountSubType;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeEntity;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReportTypeFieldEntity;
import org.cardanofoundation.lob.app.organisation.domain.request.ReportTypeFieldUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReportTypeView;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeFieldRepository;
import org.cardanofoundation.lob.app.organisation.repository.ReportTypeRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTypeService {

    private final ReportTypeRepository reportTypeRepository;
    private final ReportTypeFieldRepository reportTypeFieldRepository;
    private final OrganisationChartOfAccountSubTypeRepository organisationChartOfAccountSubTypeRepository;

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
            if(optionalReportField.isEmpty()) {
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
}
