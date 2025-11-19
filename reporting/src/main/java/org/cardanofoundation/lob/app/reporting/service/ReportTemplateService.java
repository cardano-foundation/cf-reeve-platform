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

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;
    private final ReportTemplateMapper reportTemplateMapper;
    private final ChartOfAccountSubTypeRepository chartOfAccountSubTypeRepository;

    public Either<Problem, ReportTemplateResponseDto> create(ReportTemplateDto dto) {
        log.info("Creating report template: {}", dto.getName());

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        ReportTemplateEntity entity = reportTemplateMapper.toEntity(dto, null);
        ReportTemplateEntity saved = reportTemplateRepository.save(entity);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    @Transactional(readOnly = true)
    public Optional<ReportTemplateResponseDto> findById(Long id) {
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

    public Either<Problem, ReportTemplateResponseDto> update(Long id, ReportTemplateDto dto) {
        log.info("Updating report template id: {}", id);

        // Validate subtypes exist
        Either<Problem, Void> subtypeValidation = validateSubTypes(dto.getFields());
        if (subtypeValidation.isLeft()) {
            return Either.left(subtypeValidation.getLeft());
        }

        Optional<ReportTemplateEntity> existingOpt = reportTemplateRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return Either.left(Problem.builder()
                .withTitle("Report Template Not Found")
                .withDetail("Report template with ID " + id + " does not exist")
                .withStatus(Status.NOT_FOUND)
                .build());
        }

        ReportTemplateEntity existing = existingOpt.get();

        // Map DTO to existing entity
        ReportTemplateEntity updated = reportTemplateMapper.toEntity(dto, existing);
        ReportTemplateEntity saved = reportTemplateRepository.save(updated);
        return Either.right(reportTemplateMapper.toResponseDto(saved));
    }

    public void delete(Long id) {
        log.info("Deleting report template id: {}", id);
        reportTemplateRepository.deleteById(id);
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
