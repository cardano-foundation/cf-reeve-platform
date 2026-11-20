package org.cardanofoundation.lob.app.organisation.service;

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

import org.cardanofoundation.lob.app.organisation.domain.entity.Standard;
import org.cardanofoundation.lob.app.organisation.domain.request.StandardUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.StandardView;
import org.cardanofoundation.lob.app.organisation.repository.StandardRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;

@RequiredArgsConstructor
@Slf4j
@Service
public class StandardService {

    private final StandardRepository standardRepository;
    private final CsvParser<StandardUpdate> csvParser;
    private final Validator validator;

    public List<StandardView> getAllStandards(String orgId) {
        return standardRepository.findAllByOrganisationId(orgId).stream()
                .map(regime -> StandardView.createSuccess(regime.getId().getCode(), regime.getLabel(), regime.isActive()))
                .toList();
    }

    public Set<Standard> findAllByOrganisationId(String organisationId) {
        return standardRepository.findAllByOrganisationId(organisationId);
    }

    public Optional<StandardView> getStandard(String orgId, String code) {
        return standardRepository.findById(new Standard.Id(orgId, code))
                .map(regime -> StandardView.createSuccess(regime.getId().getCode(), regime.getLabel(), regime.isActive()));
    }

    public StandardView updateStandard(String orgId, @Valid StandardUpdate standardUpdate) {
        return standardRepository.findById(new Standard.Id(orgId, standardUpdate.getCode()))
                .map(regime -> {
                    regime.setLabel(standardUpdate.getLabel());
                    regime.setActive(standardUpdate.getActive());
                    Standard updatedEntity = standardRepository.save(regime);
                    return StandardView.createSuccess(updatedEntity.getId().getCode(), updatedEntity.getLabel(), updatedEntity.isActive());
                })
                .orElseGet(() -> {
                    ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Standard with code " + standardUpdate.getCode() + " not found");
                    error.setTitle(ErrorTitleConstants.STANDARD_NOT_FOUND);
                    return StandardView.createFail(error, standardUpdate);
                });
    }

    public StandardView insertStandard(String orgId, @Valid StandardUpdate standardUpdate, boolean isUpsert) {
        Optional<Standard> regimeFound = standardRepository.findById(new Standard.Id(orgId, standardUpdate.getCode()));
        Standard regime = new Standard(new Standard.Id(orgId, standardUpdate.getCode()), standardUpdate.getLabel(), standardUpdate.getActive());
        if (regimeFound.isPresent()) {
            if (isUpsert) {
                regime = regimeFound.get();
                regime.setLabel(standardUpdate.getLabel());
                regime.setActive(standardUpdate.getActive());
            } else {
                ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Standard with code " + standardUpdate.getCode() + " already exists");
                error.setTitle(ErrorTitleConstants.STANDARD_ALREADY_EXISTS);
                return StandardView.createFail(error, standardUpdate);
            }
        }
        Standard save = standardRepository.save(regime);
        return StandardView.createSuccess(save.getId().getCode(), save.getLabel(), save.isActive());
    }

    public Either<ProblemDetail, List<StandardView>> insertViaCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, StandardUpdate.class).fold(
                Either::left,
                regimeUpdates -> Either.right(regimeUpdates.stream().map(regimeUpdate -> {
                    Errors errors = validator.validateObject(regimeUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail error = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        error.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return StandardView.createFail(error, regimeUpdate);
                    }
                    return insertStandard(orgId, regimeUpdate, true);
                }).toList())
        );
    }

}
