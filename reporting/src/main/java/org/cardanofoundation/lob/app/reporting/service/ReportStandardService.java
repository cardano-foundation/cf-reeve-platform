package org.cardanofoundation.lob.app.reporting.service;

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
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardDto;
import org.cardanofoundation.lob.app.reporting.dto.ReportStandardResponseDto;
import org.cardanofoundation.lob.app.reporting.model.entity.ReportStandardEntity;
import org.cardanofoundation.lob.app.reporting.repository.ReportStandardRepository;
import org.cardanofoundation.lob.app.reporting.util.Constants;

@RequiredArgsConstructor
@Slf4j
@Service
public class ReportStandardService {

    private final ReportStandardRepository reportStandardRepository;
    private final CsvParser<ReportStandardDto> csvParser;
    private final Validator validator;

    public List<ReportStandardResponseDto> getAllReportStandards(String orgId) {
        return reportStandardRepository.findAllByOrganisationId(orgId).stream()
                .map(standard -> ReportStandardResponseDto.createSuccess(standard.getId().getCode(), standard.getLabel(), standard.isActive()))
                .toList();
    }

    public Set<ReportStandardEntity> findAllByOrganisationId(String organisationId) {
        return reportStandardRepository.findAllByOrganisationId(organisationId);
    }

    public Optional<ReportStandardResponseDto> getReportStandard(String orgId, String code) {
        return reportStandardRepository.findById(new ReportStandardEntity.Id(orgId, code))
                .map(standard -> ReportStandardResponseDto.createSuccess(standard.getId().getCode(), standard.getLabel(), standard.isActive()));
    }

    public ReportStandardResponseDto updateReportStandard(String orgId, @Valid ReportStandardDto reportStandardUpdate) {
        return reportStandardRepository.findById(new ReportStandardEntity.Id(orgId, reportStandardUpdate.getCode()))
                .map(standard -> {
                    standard.setLabel(reportStandardUpdate.getLabel());
                    standard.setActive(reportStandardUpdate.getActive());
                    ReportStandardEntity updatedEntity = reportStandardRepository.save(standard);
                    return ReportStandardResponseDto.createSuccess(updatedEntity.getId().getCode(), updatedEntity.getLabel(), updatedEntity.isActive());
                })
                .orElseGet(() -> {
                    ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Standard with code " + reportStandardUpdate.getCode() + " not found");
                    error.setTitle(Constants.REPORT_STANDARD_NOT_FOUND);
                    return ReportStandardResponseDto.createFail(error, reportStandardUpdate);
                });
    }

    public ReportStandardResponseDto insertReportStandard(String orgId, @Valid ReportStandardDto reportStandardUpdate, boolean isUpsert) {
        Optional<ReportStandardEntity> standardFound = reportStandardRepository.findById(new ReportStandardEntity.Id(orgId, reportStandardUpdate.getCode()));
        ReportStandardEntity standard = new ReportStandardEntity(new ReportStandardEntity.Id(orgId, reportStandardUpdate.getCode()), reportStandardUpdate.getLabel(), reportStandardUpdate.getActive());
        if (standardFound.isPresent()) {
            if (isUpsert) {
                standard = standardFound.get();
                standard.setLabel(reportStandardUpdate.getLabel());
                standard.setActive(reportStandardUpdate.getActive());
            } else {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Standard with code " + reportStandardUpdate.getCode() + " already exists");
                error.setTitle(Constants.REPORT_STANDARD_ALREADY_EXISTS);
                return ReportStandardResponseDto.createFail(error, reportStandardUpdate);
            }
        }
        ReportStandardEntity save = reportStandardRepository.save(standard);
        return ReportStandardResponseDto.createSuccess(save.getId().getCode(), save.getLabel(), save.isActive());
    }

    public Either<ProblemDetail, List<ReportStandardResponseDto>> insertViaCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ReportStandardDto.class).fold(
                Either::left,
                standardUpdates -> Either.right(standardUpdates.stream().map(standardUpdate -> {
                    Errors errors = validator.validateObject(standardUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        error.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return ReportStandardResponseDto.createFail(error, standardUpdate);
                    }
                    return insertReportStandard(orgId, standardUpdate, true);
                }).toList())
        );
    }

}
