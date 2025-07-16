package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.csv.CostCenterUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.CostCenterView;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final CsvParser<CostCenterUpdate> csvParser;

    public Optional<CostCenter> getCostCenter(String organisationId, String customerCode) {
        return costCenterRepository.findByIdAndActive(new CostCenter.Id(organisationId, customerCode),true);
    }

    public Set<CostCenter> getAllCostCenter(String organisationId){
        return costCenterRepository.findAllByOrganisationId(organisationId);
    }

    @Transactional
    public CostCenterView updateCostCenter(String orgId, CostCenterUpdate costCenterUpdate) {
        Optional<CostCenter> costCenterFound = getCostCenter(orgId, costCenterUpdate.getCustomerCode());
        if(costCenterFound.isPresent()) {
            CostCenter costCenter = costCenterFound.get();
            costCenter.setName(costCenterUpdate.getName());
            costCenter.setActive(costCenterUpdate.isActive());
            // check if parent exists
            if (costCenterUpdate.getParentCustomerCode() != null && !costCenterUpdate.getParentCustomerCode().isBlank()) {
                Optional<CostCenter> project = getCostCenter(orgId, costCenterUpdate.getParentCustomerCode());
                if(project.isEmpty()) {
                    return CostCenterView.createFail(
                            costCenterUpdate,
                            Problem.builder()
                                    .withTitle("PARENT_COST_CENTER_CODE_NOT_FOUND")
                                    .withDetail("Parent project code with customer code %s not found.".formatted(costCenterUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }
            costCenter.setParentCustomerCode(Optional.ofNullable(costCenterUpdate.getParentCustomerCode()));
            return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
        } else {
            return CostCenterView.createFail(
                    costCenterUpdate,
                    Problem.builder()
                            .withTitle("COST_CENTER_CODE_NOT_FOUND")
                            .withDetail("Cost Center with customer code %s not found.".formatted(costCenterUpdate.getCustomerCode()))
                            .build()
            );
        }
    }

    @Transactional
    public CostCenterView insertCostCenter(String orgId, CostCenterUpdate costCenterUpdate, boolean isUpsert) {
        Optional<CostCenter> costCenterFound = getCostCenter(orgId, costCenterUpdate.getCustomerCode());
        CostCenter costCenter = new CostCenter();
        costCenter.setId(new CostCenter.Id(orgId, costCenterUpdate.getCustomerCode()));
        if(costCenterFound.isPresent()) {
            if(isUpsert) {
                costCenter = costCenterFound.get();
            } else {
                return CostCenterView.createFail(
                        costCenterUpdate,
                        Problem.builder()
                                .withTitle("COST_CENTER_CODE_ALREADY_EXISTS")
                                .withDetail("Cost Center with customer code %s already exists.".formatted(costCenterUpdate.getCustomerCode()))
                                .build()
                );
            }
        }
        costCenter.setName(costCenterUpdate.getName());
        costCenter.setActive(costCenterUpdate.isActive());

        // check if parent exists
        if (costCenterUpdate.getParentCustomerCode() != null && !costCenterUpdate.getParentCustomerCode().isBlank()) {
            Optional<CostCenter> parent = getCostCenter(orgId, costCenterUpdate.getParentCustomerCode());
            if(parent.isPresent()) {
                costCenter.setParent(parent.get());
            } else {
                return CostCenterView.createFail(
                        costCenterUpdate,
                        Problem.builder()
                                .withTitle("PARENT_COST_CENTER_CODE_NOT_FOUND")
                                .withDetail("Parent project code with customer code %s not found.".formatted(costCenterUpdate.getParentCustomerCode()))
                                .build()
                );
            }
        }
        return CostCenterView.fromEntity(costCenterRepository.save(costCenter));
    }

    @Transactional
    public Either<Problem, List<CostCenterView>> createCostCenterFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CostCenterUpdate.class).fold(
                Either::left,
                costCenterUpdates -> Either.right(costCenterUpdates.stream().map(costCenterUpdate -> insertCostCenter(orgId, costCenterUpdate, true)).toList())
        );
    }

}
