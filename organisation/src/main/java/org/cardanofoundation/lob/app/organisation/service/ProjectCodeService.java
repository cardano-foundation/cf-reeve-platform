package org.cardanofoundation.lob.app.organisation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationProjectView;
import org.cardanofoundation.lob.app.organisation.repository.ProjectMappingRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectCodeService {

    private final ProjectMappingRepository projectMappingRepository;
    private final CsvParser<ProjectUpdate> csvParser;

    public Optional<OrganisationProject> getProject(String organisationId, String customerCode) {
        return projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode));
    }

    @Transactional
    public Either<Problem, List<OrganisationProjectView>> createProjectCodeFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ProjectUpdate.class).fold(
                Either::left,
                projectUpdates -> {
                    List<OrganisationProjectView> views = new ArrayList<>();
                    for(ProjectUpdate projectUpdate : projectUpdates) {
                        getProject(orgId, projectUpdate.getCustomerCode()).ifPresentOrElse(
                                project -> {
                                    views.add(OrganisationProjectView.createFail(
                                            projectUpdate.getCustomerCode(),
                                            Problem.builder()
                                                    .withTitle("PROJECT_CODE_ALREADY_EXISTS")
                                                    .withDetail("Project code with customer code " + projectUpdate.getCustomerCode() + " already exists.")
                                                    .build()
                                    ));
                                },
                                () -> {
                                    OrganisationProject.OrganisationProjectBuilder builder = OrganisationProject.builder()
                                                    .id(new OrganisationProject.Id(orgId, projectUpdate.getCustomerCode()))
                                                    .externalCustomerCode(projectUpdate.getExternalCustomerCode())
                                                    .name(projectUpdate.getName());

                                    // check if parent exists
                                    if (projectUpdate.getParentCustomerCode() != null) {
                                        Optional<OrganisationProject> project = getProject(orgId, projectUpdate.getParentCustomerCode());
                                        if(project.isPresent()) {
                                            builder.parentCustomerCode(project.get().getId().getCustomerCode());
                                        } else {
                                            views.add(OrganisationProjectView.createFail(
                                                    projectUpdate.getCustomerCode(),
                                                    Problem.builder()
                                                            .withTitle("PARENT_PROJECT_CODE_NOT_FOUND")
                                                            .withDetail("Parent project code with customer code " + projectUpdate.getParentCustomerCode() + " not found.")
                                                            .build()
                                            ));
                                            return;
                                        }
                                    }
                                    projectMappingRepository.save(builder.build());
                                    views.add(OrganisationProjectView.fromEntity(builder.build()));
                                }
                        );
                    }
                    return Either.right(views);
                }
        );

    }
}
