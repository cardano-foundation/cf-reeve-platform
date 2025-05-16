package org.cardanofoundation.lob.app.organisation.service;

import java.util.ArrayList;
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
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCostCenter;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationCostCenterView;
import org.cardanofoundation.lob.app.organisation.repository.CostCenterRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostCenterService {

    private final CostCenterRepository costCenterRepository;
    private final CsvParser<CostCenterUpdate> csvParser;

    public Optional<OrganisationCostCenter> getCostCenter(String organisationId, String customerCode) {
        return costCenterRepository.findById(new OrganisationCostCenter.Id(organisationId, customerCode));
    }

    public Set<OrganisationCostCenter> getAllCostCenter(String organisationId){
        return costCenterRepository.findAllByOrganisationId(organisationId);
    }

    @Transactional
    public Either<Problem, List<OrganisationCostCenterView>> createCostCenterFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, CostCenterUpdate.class).fold(
                Either::left,
                costCenterUpdates -> {
                    List<OrganisationCostCenterView> costCenterViews = new ArrayList<>();
                    for (CostCenterUpdate costCenterUpdate : costCenterUpdates) {
                        getCostCenter(orgId, costCenterUpdate.getCustomerCode()).ifPresentOrElse(
                                costCenter -> {
                                    costCenterViews.add(OrganisationCostCenterView.createFail(
                                            costCenterUpdate.getCustomerCode(),
                                            Problem.builder()
                                                    .withTitle("COST_CENTER_ALREADY_EXISTS")
                                                    .withDetail("Cost Center with customer code " + costCenterUpdate.getCustomerCode() + " already exists.")
                                                    .build()
                                    ));
                                },
                                () -> {
                                    OrganisationCostCenter.OrganisationCostCenterBuilder builder = OrganisationCostCenter.builder()
                                            .id(new OrganisationCostCenter.Id(orgId, costCenterUpdate.getCustomerCode()))
                                            .externalCustomerCode(costCenterUpdate.getExternalCustomerCode())
                                            .name(costCenterUpdate.getName());

                                    // check if parent exists
                                    if (costCenterUpdate.getParentCustomerCode() != null) {
                                        Optional<OrganisationCostCenter> project = getCostCenter(orgId, costCenterUpdate.getParentCustomerCode());
                                        if(project.isPresent()) {
                                            builder.parentCustomerCode(project.get().getId().getCustomerCode());
                                        } else {
                                            costCenterViews.add(OrganisationCostCenterView.createFail(
                                                    costCenterUpdate.getCustomerCode(),
                                                    Problem.builder()
                                                            .withTitle("PARENT_COST_CENTER_CODE_NOT_FOUND")
                                                            .withDetail("Parent project code with customer code " + costCenterUpdate.getParentCustomerCode() + " not found.")
                                                            .build()
                                            ));
                                            return;
                                        }
                                    }
                                    costCenterRepository.save(builder.build());
                                    costCenterViews.add(OrganisationCostCenterView.fromEntity(builder.build()));
                                }
                        );
                    }
                    return Either.right(costCenterViews);
                }
        );
    }

}
