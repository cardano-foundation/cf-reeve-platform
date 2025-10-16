package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.util.SortFieldMappings;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceCodeService {

    private final ReferenceCodeRepository referenceCodeRepository;
    private final OrganisationService organisationService;
    private final CsvParser<ReferenceCodeUpdate> csvParser;
    private final AccountEventService accountEventService;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Either<Problem, List<ReferenceCodeView>> getAllReferenceCodes(String orgId, String referenceCode, String name, List<String> parentCodes, Boolean active, Pageable pageable) {
<<<<<<< HEAD
        Either<Problem, Pageable> validateEntity = jpaSortFieldValidator.validateEntity(
                ReferenceCode.class, pageable,
                SortFieldMappings.REFERENCE_CODE_MAPPINGS);
        if(validateEntity.isLeft()) {
            return Either.left(validateEntity.left().get());
        }
        pageable = validateEntity.get();
=======
>>>>>>> release/1.2.0
        if(parentCodes != null) {
            // Lower case to avoid case sensitivity issues
            parentCodes = parentCodes.stream().filter(s -> s != null && !s.isEmpty()).map(String::toLowerCase).collect(Collectors.toList());
        }
        return Either.right(referenceCodeRepository.findAllByOrgId(orgId, referenceCode, name, parentCodes, active, pageable).stream()
                .map(ReferenceCodeView::fromEntity)
                .toList());
    }

    public Optional<ReferenceCodeView> getReferenceCode(String orgId, String referenceCode) {
        return referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCode)
                .map(ReferenceCodeView::fromEntity);
    }

    @Transactional
    public ReferenceCodeView insertReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate, boolean isUpsert) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }
        if (referenceCodeUpdate.getParentReferenceCode() != null && !referenceCodeUpdate.getParentReferenceCode().isEmpty()) {
            Optional<ReferenceCode> parentReferenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getParentReferenceCode());
            if (parentReferenceCode.isEmpty()) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle(ErrorTitleConstants.PARENT_REFERENCE_CODE_NOT_FOUND)
                        .withDetail("Unable to find parent reference Id: %s".formatted(referenceCodeUpdate.getParentReferenceCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(),
                        referenceCodeUpdate);
            }
            if (parentReferenceCode.get().getId().getReferenceCode().equals(referenceCodeUpdate.getReferenceCode())) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle("PARENT_REFERENCE_CODE_CANNOT_BE_SELF")
                        .withDetail("The parent reference code cannot be the same as the reference code itself :%s".formatted(referenceCodeUpdate.getReferenceCode()))
                        .withStatus(Status.BAD_REQUEST)
                        .build(), referenceCodeUpdate);
            }
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());
        ReferenceCode referenceCode = ReferenceCode.builder()
                .id(new ReferenceCode.Id(orgId, referenceCodeUpdate.getReferenceCode()))
                .build();
        if(referenceCodeOpt.isPresent()){
            if(isUpsert) {
                referenceCode = referenceCodeOpt.get();
            } else {
                return ReferenceCodeView.createFail(Problem.builder()
                                .withTitle(ErrorTitleConstants.REFERENCE_CODE_ALREADY_EXIST)
                                .withDetail("The reference code with code :%s already exists".formatted(referenceCodeUpdate.getReferenceCode()))
                                .withStatus(Status.CONFLICT)
                                .build(),
                        referenceCodeUpdate);
            }
        }


        referenceCode.setName(referenceCodeUpdate.getName());
        referenceCode.setParentReferenceCode(referenceCodeUpdate.getParentReferenceCode() == null || referenceCodeUpdate.getParentReferenceCode().isEmpty() ? null : referenceCodeUpdate.getParentReferenceCode());

        referenceCode.setActive(referenceCodeUpdate.getActive());
        ReferenceCode savedEntity = referenceCodeRepository.save(referenceCode);
        // updating event codes
        accountEventService.updateStatus(orgId, savedEntity.getId().getReferenceCode());
        // The reference code returning is not the latest version after save
        return ReferenceCodeView.fromEntity(savedEntity);
    }

    @Transactional
    public ReferenceCodeView updateReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND)
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }
        if (referenceCodeUpdate.getParentReferenceCode() != null && !referenceCodeUpdate.getParentReferenceCode().isEmpty()) {
            Optional<ReferenceCode>  parentReferenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getParentReferenceCode());
            if (parentReferenceCode.isEmpty()) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle(ErrorTitleConstants.PARENT_REFERENCE_CODE_NOT_FOUND)
                        .withDetail("Unable to find parent reference Id: %s".formatted(referenceCodeUpdate.getParentReferenceCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(),
                        referenceCodeUpdate);
            }
            if (parentReferenceCode.get().getId().getReferenceCode().equals(referenceCodeUpdate.getReferenceCode())) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle("PARENT_REFERENCE_CODE_CANNOT_BE_SELF")
                        .withDetail("The parent reference code cannot be the same as the reference code itself :%s".formatted(referenceCodeUpdate.getReferenceCode()))
                        .withStatus(Status.BAD_REQUEST)
                        .build(), referenceCodeUpdate);
            }
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());

        if(referenceCodeOpt.isEmpty()){
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND)
                    .withDetail("Unable to find reference Id: %s".formatted(referenceCodeUpdate.getReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }

        ReferenceCode referenceCode = referenceCodeOpt.get();
        referenceCode.setName(referenceCodeUpdate.getName());
        referenceCode.setParentReferenceCode(referenceCodeUpdate.getParentReferenceCode() == null || referenceCodeUpdate.getParentReferenceCode().isEmpty() ? null : referenceCodeUpdate.getParentReferenceCode());

        referenceCode.setActive(referenceCodeUpdate.getActive());
        // The reference code returning is not the latest version after save
        referenceCode = referenceCodeRepository.save(referenceCode);
        accountEventService.updateStatus(orgId, referenceCode.getId().getReferenceCode());

        return ReferenceCodeView.fromEntity(referenceCode);
    }

    @Transactional
    public Either<List<Problem>, List<ReferenceCodeView>> insertReferenceCodeByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ReferenceCodeUpdate.class).fold(
                problem -> Either.left(List.of(problem)),
                referenceCodeUpdates -> Either.right(referenceCodeUpdates.stream().map(
                        refCodeUpdate -> {
                            Errors errors = validator.validateObject(refCodeUpdate);
                            List<ObjectError> allErrors = errors.getAllErrors();
                            if (!allErrors.isEmpty()) {
                                return ReferenceCodeView.createFail(Problem.builder()
                                        .withTitle(ErrorTitleConstants.VALIDATION_ERROR)
                                        .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                                        .withStatus(Status.BAD_REQUEST)
                                        .build(), refCodeUpdate);
                            }
                            return insertReferenceCode(orgId, refCodeUpdate, true);
                        }).toList())
        );
    }

}
