package org.cardanofoundation.lob.app.reporting.service;

import java.util.HashSet;
import java.util.List;
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

import org.cardanofoundation.lob.app.organisation.repository.ChartOfAccountSubTypeRepository;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateFieldDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportTemplateResponseDto;
import org.cardanofoundation.lob.app.reporting.mapper.ReportTemplateMapper;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportTemplateEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportTemplateRepository;
import org.cardanofoundation.lob.app.reporting.repository.ReportingRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;
    private final ReportingRepository reportingRepository;

    public Either<Problem, ReportTemplateResponseDto> create(ReportTemplateDto dto) {
        log.info("Creating report template: {}", dto.getName());

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        // Check if a template with the same name already exists for this organisation
        Optional<ReportTemplateEntity> existingTemplateOpt =
                reportTemplateRepository.findLatestByOrganisationIdAndName(dto.getOrganisationId(), dto.getName());

        ReportTemplateEntity templateToSave;

        if (existingTemplateOpt.isPresent()) {
            ReportTemplateEntity existing = existingTemplateOpt.get();

            // Check if there are any reports using this template
            List<org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity> existingReports =
                    reportingRepository.findByReportTemplateId(existing.getId());

            if (!existingReports.isEmpty()) {
                // Reports exist - create a new version
                log.info("Template '{}' has {} existing reports, creating new version {} -> {}",
                        dto.getName(), existingReports.size(), existing.getVer(), existing.getVer() + 1);

                templateToSave = reportTemplateMapper.toEntity(dto, null);
                templateToSave.setVer(existing.getVer() + 1);
            } else {
                // No reports exist - update existing template in place
                log.info("Template '{}' has no existing reports, updating in place", dto.getName());
                templateToSave = reportTemplateMapper.toEntity(dto, existing);
            }
        } else {
            // New template - create with version 1
            log.info("Creating new template: {}", dto.getName());
            templateToSave = reportTemplateMapper.toEntity(dto, null);
        }

        ReportTemplateEntity saved = reportTemplateRepository.save(templateToSave);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    @Transactional(readOnly = true)
    public Optional<ReportTemplateResponseDto> findById(String id) {
        return reportTemplateRepository.findById(id)
            .map(reportTemplateMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public List<ReportTemplateResponseDto> findByOrganisationId(String organisationId) {
        return reportTemplateRepository.findByOrganisationId(organisationId).stream()
            .map(reportTemplateMapper::toResponseDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportTemplateResponseDto> findAll() {
        return reportTemplateRepository.findAll().stream()
            .map(reportTemplateMapper::toResponseDto)
            .toList();
    }

    public Either<Problem, Void> delete(String id) {
        log.info("Deleting report template id: {}", id);

        Optional<ReportTemplateEntity> templateOpt = reportTemplateRepository.findById(id);
        if (templateOpt.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Report Template Not Found")
                    .withDetail("Report template with ID " + id + " does not exist")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        // Check if there are any reports using this template
        List<org.cardanofoundation.lob.app.reporting.model.entity.ReportEntity> existingReports =
                reportingRepository.findByReportTemplateId(id);

        if (!existingReports.isEmpty()) {
            return Either.left(Problem.builder()
                    .withTitle("Template Has Associated Reports")
                    .withDetail("Cannot delete template with ID " + id + " because it has " +
                               existingReports.size() + " associated report(s)")
                    .withStatus(Status.BAD_REQUEST)
                    .build());
        }

        reportTemplateRepository.deleteById(id);
        return Either.right(null);
    }

    @Transactional(readOnly = true)
    public boolean existsByOrganisationIdAndName(String organisationId, String name) {
        return reportTemplateRepository.existsByOrganisationIdAndName(organisationId, name);
    }

    private Either<Problem, Void> validateSubTypes(List<ReportTemplateFieldDto> fields) {
        if (fields == null || fields.isEmpty()) {
            return Either.right(null);
        }

        // Collect all subtype IDs from all fields (including nested)
        Set<Long> allSubTypeIds = new HashSet<>();
        collectSubTypeIds(fields, allSubTypeIds);

        if (allSubTypeIds.isEmpty()) {
            return Either.right(null);
        }

        // Convert Long IDs to String for repository lookup
        List<String> stringIds = allSubTypeIds.stream()
            .map(String::valueOf)
            .collect(Collectors.toList());

        // Fetch existing subtypes
        List<String> existingSubTypeIds = chartOfAccountSubTypeRepository.findAllById(stringIds).stream()
            .map(subType -> String.valueOf(subType.getId()))
            .collect(Collectors.toList());

        // Find missing subtypes
        Set<String> missingSubTypeIds = stringIds.stream()
            .filter(id -> !existingSubTypeIds.contains(id))
            .collect(Collectors.toSet());

        if (!missingSubTypeIds.isEmpty()) {
            return Either.left(Problem.builder()
                .withTitle("Invalid SubType IDs")
                .withDetail("The following subtype IDs do not exist: " + missingSubTypeIds)
                .withStatus(Status.BAD_REQUEST)
                .build());
        }

        return Either.right(null);
    }

    private void collectSubTypeIds(List<ReportTemplateFieldDto> fields, Set<Long> subTypeIds) {
        if (fields == null) {
            return;
        }

        for (ReportTemplateFieldDto field : fields) {
            if (field.getMappingSubTypeIds() != null) {
                subTypeIds.addAll(field.getMappingSubTypeIds());
            }
            if (field.getChildFields() != null) {
                collectSubTypeIds(field.getChildFields(), subTypeIds);
            }
        }
    }
}
