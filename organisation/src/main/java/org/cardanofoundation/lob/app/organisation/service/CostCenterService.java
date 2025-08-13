package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.CostCenterView;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final CsvParser<CostCenterUpdate> csvParser;
    private final Validator validator;

    public Optional<CostCenter> getCostCenter(String organisationId, String customerCode) {
        return costCenterRepository.findByIdAndActive(new CostCenter.Id(organisationId, customerCode), true);
    }

    public Set<CostCenter> getAllCostCenter(String organisationId) {
        return costCenterRepository.findAllByOrganisationIdWithParentAndChildren(organisationId);
    }

    @Transactional
    public CostCenterView updateCostCenter(String orgId, CostCenterUpdate costCenterUpdate) {
        Optional<CostCenter> costCenterFound = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        if (costCenterFound.isPresent()) {
            CostCenter costCenter = costCenterFound.get();
            costCenter.setName(costCenterUpdate.getName());

            // check if parent exists
            if (costCenterUpdate.getParentCustomerCode() != null && !costCenterUpdate.getParentCustomerCode().isBlank()) {
                Optional<CostCenter> costCenterOptional = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getParentCustomerCode()));
                if (costCenterOptional.isEmpty()) {
                    return CostCenterView.createFail(
                            costCenterUpdate,
                            Problem.builder()
                                    .withStatus(Status.NOT_FOUND)
                                    .withTitle(ErrorTitleConstants.PARENT_COST_CENTER_CODE_NOT_FOUND)
                                    .withDetail("Parent project code with customer code %s not found.".formatted(costCenterUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }
            costCenter.setParentCustomerCode(costCenterUpdate.getParentCustomerCode() == null || costCenterUpdate.getParentCustomerCode().isBlank() ? null : costCenterUpdate.getParentCustomerCode());
            costCenter.setActive(costCenterUpdate.isActive());
            return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
        }
        return CostCenterView.createFail(
                costCenterUpdate,
                Problem.builder()
                        .withStatus(Status.NOT_FOUND)
                        .withTitle("COST_CENTER_CODE_NOT_FOUND")
                        .withDetail("Cost Center with customer code %s not found.".formatted(costCenterUpdate.getCustomerCode()))
                        .build()
        );

    }

    @Transactional
    public CostCenterView insertCostCenter(String orgId, CostCenterUpdate costCenterUpdate, boolean isUpsert) {
        Optional<CostCenter> costCenterFound = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        CostCenter costCenter = new CostCenter();
        costCenter.setId(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        if (costCenterFound.isPresent()) {
            if (!isUpsert) {
                return CostCenterView.createFail(
                        costCenterUpdate,
                        Problem.builder()
                                .withStatus(Status.CONFLICT)
                                .withTitle(ErrorTitleConstants.COST_CENTER_CODE_ALREADY_EXISTS)
                                .withDetail("Cost Center with customer code %s already exists.".formatted(costCenterUpdate.getCustomerCode()))
                                .build()
                );
            }
            costCenter = costCenterFound.get();
        }
        costCenter.setName(costCenterUpdate.getName());
        costCenter.setActive(costCenterUpdate.isActive());

        // check if parent exists
        if (costCenterUpdate.getParentCustomerCode() != null && !costCenterUpdate.getParentCustomerCode().isBlank()) {
            Optional<CostCenter> parent = costCenterRepository.findById(new CostCenter.Id(orgId, costCenterUpdate.getParentCustomerCode()));
            if (parent.isEmpty()) {
                return CostCenterView.createFail(
                        costCenterUpdate,
                        Problem.builder()
                                .withTitle(ErrorTitleConstants.PARENT_COST_CENTER_CODE_NOT_FOUND)
                                .withDetail("Parent project code with customer code %s not found.".formatted(costCenterUpdate.getParentCustomerCode()))
                                .build()
                );
            }
                costCenter.setParentCustomerCode(Objects.requireNonNull(parent.get().getId()).getCustomerCode());
        }

        return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
    }

    @Transactional
    public Either<Problem, List<CostCenterView>> createCostCenterFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CostCenterUpdate.class).fold(
                Either::left,
                costCenterUpdates -> Either.right(costCenterUpdates.stream().map(costCenterUpdate -> {
                    Errors errors = validator.validateObject(costCenterUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        return CostCenterView.createFail(costCenterUpdate,Problem.builder()
                                .withTitle(ErrorTitleConstants.VALIDATION_ERROR)
                                .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                                .withStatus(Status.BAD_REQUEST)
                                .build());
                    }
                    return insertCostCenter(orgId, costCenterUpdate, true);
                }).toList())
        );
    }

}
