package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.COST_CENTER_MAPPINGS;

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

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.CostCenterView;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final CsvParser<CostCenterUpdate> csvParser;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Optional<CostCenter> getCostCenter(String organisationId, String customerCode) {
        return costCenterRepository.findByIdAndActive(new CostCenter.Id(organisationId, customerCode), true);
    }

    public Either<ProblemDetail, List<CostCenterView>> getAllCostCenter(String organisationId, String customerCode, String name, List<String> parentCustomerCodes, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> pageables = jpaSortFieldValidator.validateEntity(CostCenter.class, pageable, COST_CENTER_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        return Either.right(costCenterRepository.findAllByOrganisationId(organisationId, customerCode, name, parentCustomerCodes, active, pageable).map(CostCenterView::fromEntity).toList());
    }

    @Transactional
    public CostCenterView updateCostCenter(String orgId, CostCenterUpdate costCenterUpdate) {
        Optional<CostCenter> costCenterFound = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        if (costCenterFound.isPresent()) {
            CostCenter costCenter = costCenterFound.get();
            costCenter.setName(costCenterUpdate.getName());

            // check if parent exists
            Either<CostCenterView, Void> parentExistsCheck = checkIfParentExists(orgId, costCenterUpdate);
            if(parentExistsCheck.isLeft()) {
                return parentExistsCheck.getLeft();
            }
            costCenter.setParentCustomerCode(costCenterUpdate.getParentCustomerCode() == null || costCenterUpdate.getParentCustomerCode().isBlank() ? null : costCenterUpdate.getParentCustomerCode());
            costCenter.setActive(costCenterUpdate.getActive());
            return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Cost Center with customer code %s not found.".formatted(costCenterUpdate.getCustomerCode()));
        problem.setTitle("COST_CENTER_CODE_NOT_FOUND");
        return CostCenterView.createFail(costCenterUpdate, problem);
    }

    private Either<CostCenterView, Void> checkIfParentExists(String orgId, CostCenterUpdate costCenterUpdate) {
        if (costCenterUpdate.getParentCustomerCode() != null && !costCenterUpdate.getParentCustomerCode().isBlank()) {
            Optional<CostCenter> parent = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getParentCustomerCode()));
            if (parent.isEmpty()) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Parent project code with customer code %s not found.".formatted(costCenterUpdate.getParentCustomerCode()));
                problem.setTitle(ErrorTitleConstants.PARENT_COST_CENTER_CODE_NOT_FOUND);
                return Either.left(CostCenterView.createFail(costCenterUpdate, problem));
            }
            if (parent.get().getId().getCustomerCode().equals(costCenterUpdate.getCustomerCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent cost center cannot be the same as the cost center itself: %s".formatted(costCenterUpdate.getCustomerCode()));
                problem.setTitle("PARENT_COST_CENTER_CANNOT_BE_SELF");
                return Either.left(CostCenterView.createFail(costCenterUpdate, problem));
            }
            if (Optional.ofNullable(parent.get().getParentCustomerCode()).orElse("").equals(costCenterUpdate.getCustomerCode())) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent cost center cannot have a circular reference with the cost center itself: %s".formatted(costCenterUpdate.getCustomerCode()));
                problem.setTitle("CIRCULAR_REFERENCE");
                return Either.left(CostCenterView.createFail(costCenterUpdate, problem));
            }
        }
        return Either.right(null);
    }

    @Transactional
    public CostCenterView insertCostCenter(String orgId, CostCenterUpdate costCenterUpdate, boolean isUpsert) {
        Optional<CostCenter> costCenterFound = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        CostCenter costCenter = new CostCenter();
        costCenter.setId(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        if (costCenterFound.isPresent()) {
            if (!isUpsert) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Cost Center with customer code %s already exists.".formatted(costCenterUpdate.getCustomerCode()));
                problem.setTitle(ErrorTitleConstants.COST_CENTER_CODE_ALREADY_EXISTS);
                return CostCenterView.createFail(costCenterUpdate, problem);
            }
            costCenter = costCenterFound.get();
        }
        costCenter.setName(costCenterUpdate.getName());
        costCenter.setActive(costCenterUpdate.getActive());

        // check if parent exists
        Either<CostCenterView, Void> parentExistsCheck = checkIfParentExists(orgId, costCenterUpdate);
        if(parentExistsCheck.isLeft()) {
            return parentExistsCheck.getLeft();
        }
        costCenter.setParentCustomerCode(costCenterUpdate.getParentCustomerCode() == null || costCenterUpdate.getParentCustomerCode().isBlank() ? null : costCenterUpdate.getParentCustomerCode());

        return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
    }

    @Transactional
    public Either<ProblemDetail, List<CostCenterView>> createCostCenterFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CostCenterUpdate.class).fold(
                problemDetail -> Either.left(problemDetail),
                costCenterUpdates -> Either.right(costCenterUpdates.stream().map(costCenterUpdate -> {
                    Errors errors = validator.validateObject(costCenterUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        problem.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return CostCenterView.createFail(costCenterUpdate, problem);
                    }
                    return insertCostCenter(orgId, costCenterUpdate, true);
                }).toList())
        );
    }

    public void downloadCsv(String orgId, String customerCode, String name, List<String> parentCustomerCodes, Boolean active, OutputStream outputStream) {
        Page<CostCenter> allCostCenters = costCenterRepository.findAllByOrganisationId(orgId, customerCode, name, parentCustomerCodes, active, Pageable.unpaged());
        try(Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Customer code", "Name", "Parent customer code", "Active"};
            csvWriter.writeNext(header, false);
            for (CostCenter costCenter : allCostCenters) {
                String[] data = {
                        costCenter.getId().getCustomerCode(),
                        costCenter.getName(),
                        costCenter.getParentCustomerCode(),
                        String.valueOf(costCenter.isActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing currencies to CSV", e);
        }
    }
}
