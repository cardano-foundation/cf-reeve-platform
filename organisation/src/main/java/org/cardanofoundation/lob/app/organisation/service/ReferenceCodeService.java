package org.cardanofoundation.lob.app.organisation.service;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.CSVWriter;
import io.vavr.control.Either;

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

    public Either<ProblemDetail, List<ReferenceCodeView>> getAllReferenceCodes(String orgId, String referenceCode, String name, List<String> parentCodes, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> validateEntity = jpaSortFieldValidator.validateEntity(
                ReferenceCode.class, pageable,
                SortFieldMappings.REFERENCE_CODE_MAPPINGS);
        if(validateEntity.isLeft()) {
            return Either.left(validateEntity.getLeft());
        }
        pageable = validateEntity.get();
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

        Either<ReferenceCodeView, Void> eventCodeUpdateChecks = checkEventCodeUpdate(orgId, referenceCodeUpdate);
        if (eventCodeUpdateChecks.isLeft()) {
            return eventCodeUpdateChecks.getLeft();
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());
        ReferenceCode referenceCode = ReferenceCode.builder()
                .id(new ReferenceCode.Id(orgId, referenceCodeUpdate.getReferenceCode()))
                .build();
        if(referenceCodeOpt.isPresent()){
            if(isUpsert) {
                referenceCode = referenceCodeOpt.get();
            } else {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The reference code with code :%s already exists".formatted(referenceCodeUpdate.getReferenceCode()));
                problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_ALREADY_EXIST);
                return ReferenceCodeView.createFail(problem, referenceCodeUpdate);
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

    private Either<ReferenceCodeView, Void> checkEventCodeUpdate(String orgId, ReferenceCodeUpdate referenceCodeUpdate) {
        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find Organisation by Id: %s".formatted(orgId));
            problem.setTitle(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
            return Either.left(ReferenceCodeView.createFail(problem, referenceCodeUpdate));
        }
        if (referenceCodeUpdate.getParentReferenceCode() != null && !referenceCodeUpdate.getParentReferenceCode().isEmpty()) {
            Optional<ReferenceCode> parentReferenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getParentReferenceCode());
            if (parentReferenceCode.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find parent reference Id: %s".formatted(referenceCodeUpdate.getParentReferenceCode()));
                problem.setTitle(ErrorTitleConstants.PARENT_REFERENCE_CODE_NOT_FOUND);
                return Either.left(ReferenceCodeView.createFail(problem, referenceCodeUpdate));
            }
            if (parentReferenceCode.get().getId().getReferenceCode().equals(referenceCodeUpdate.getReferenceCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent reference code cannot be the same as the reference code itself: %s".formatted(referenceCodeUpdate.getReferenceCode()));
                problem.setTitle("PARENT_REFERENCE_CODE_CANNOT_BE_SELF");
                return Either.left(ReferenceCodeView.createFail(problem, referenceCodeUpdate));
            }
            if (Optional.ofNullable(parentReferenceCode.get().getParentReferenceCode()).orElse("").equals(referenceCodeUpdate.getReferenceCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent reference code cannot have a cycle with itself: %s".formatted(referenceCodeUpdate.getReferenceCode()));
                problem.setTitle("CIRCULAR_REFERENCE");
                return Either.left(ReferenceCodeView.createFail(problem, referenceCodeUpdate));
            }
        }
        return Either.right(null);
    }

    @Transactional
    public ReferenceCodeView updateReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate) {

        Either<ReferenceCodeView, Void> eventCodeUpdateChecks = checkEventCodeUpdate(orgId, referenceCodeUpdate);
        if (eventCodeUpdateChecks.isLeft()) {
            return eventCodeUpdateChecks.getLeft();
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());

        if(referenceCodeOpt.isEmpty()){
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unable to find reference Id: %s".formatted(referenceCodeUpdate.getReferenceCode()));
            problem.setTitle(ErrorTitleConstants.REFERENCE_CODE_NOT_FOUND);
            return ReferenceCodeView.createFail(problem, referenceCodeUpdate);
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
    public Either<List<ProblemDetail>, List<ReferenceCodeView>> insertReferenceCodeByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ReferenceCodeUpdate.class).fold(
                problem -> Either.left(List.of(problem)),
                referenceCodeUpdates -> Either.right(referenceCodeUpdates.stream().map(
                        refCodeUpdate -> {
                            Errors errors = validator.validateObject(refCodeUpdate);
                            List<ObjectError> allErrors = errors.getAllErrors();
                            if (!allErrors.isEmpty()) {
                                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                                problem.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                                return ReferenceCodeView.createFail(problem, refCodeUpdate);
                            }
                            return insertReferenceCode(orgId, refCodeUpdate, true);
                        }).toList())
        );
    }

    public void downloadCsv(String orgId, String referenceCode, String name, List<String> parentCodes, Boolean active, OutputStream outputStream) {
        Page<ReferenceCode> allRefCodes = referenceCodeRepository.findAllByOrgId(orgId, referenceCode, name, parentCodes, active, Pageable.unpaged());
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Reference Code", "Name", "Parent Reference Code", "Active"};
            csvWriter.writeNext(header, false);
            for (ReferenceCode refCode : allRefCodes) {
                String[] data = {
                        refCode.getId().getReferenceCode(),
                        refCode.getName(),
                        refCode.getParentReferenceCode(),
                        String.valueOf(refCode.isActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing reference codes to CSV for orgId {}: {}", orgId, e.getMessage());
        }
    }
}
