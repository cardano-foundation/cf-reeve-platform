package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Objects;
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
    public OrganisationProjectView insertProject(String orgId, ProjectUpdate projectUpdate) {
        Optional<OrganisationProject> projectFound = getProject(orgId, projectUpdate.getCustomerCode());
        if(projectFound.isPresent()) {
            return OrganisationProjectView.createFail(
                    projectUpdate.getCustomerCode(),
                    Problem.builder()
                            .withTitle("PROJECT_CODE_ALREADY_EXISTS")
                            .withDetail("Project code with customer code %s already exists.".formatted(projectUpdate.getCustomerCode()))
                            .build()
            );
        } else {
            OrganisationProject.OrganisationProjectBuilder builder = OrganisationProject.builder()
                    .id(new OrganisationProject.Id(orgId, projectUpdate.getCustomerCode()))
                    .externalCustomerCode(projectUpdate.getExternalCustomerCode())
                    .name(projectUpdate.getName());

            // check if parent exists
            if (projectUpdate.getParentCustomerCode() != null) {
                Optional<OrganisationProject> project = getProject(orgId, projectUpdate.getParentCustomerCode());
                if(project.isPresent()) {
                    builder.parentCustomerCode(Objects.requireNonNull(project.get().getId()).getCustomerCode());
                } else {
                    return OrganisationProjectView.createFail(
                            projectUpdate.getCustomerCode(),
                            Problem.builder()
                                    .withTitle("PARENT_PROJECT_CODE_NOT_FOUND")
                                    .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }
            OrganisationProject saved = projectMappingRepository.save(builder.build());
            return OrganisationProjectView.fromEntity(saved);
        }
    }

    @Transactional
    public OrganisationProjectView updateProject(String orgId, ProjectUpdate projectUpdate) {
        Optional<OrganisationProject> projectFound = getProject(orgId, projectUpdate.getCustomerCode());
        if(projectFound.isPresent()) {
            OrganisationProject projectEntityUpdated = projectFound.get();
            projectEntityUpdated.setExternalCustomerCode(projectUpdate.getExternalCustomerCode());
            projectEntityUpdated.setName(projectUpdate.getName());
            // check if parent exists
            if (projectUpdate.getParentCustomerCode() != null) {
                Optional<OrganisationProject> project = getProject(orgId, projectUpdate.getParentCustomerCode());
                if(project.isPresent()) {
                    projectEntityUpdated.setParentCustomerCode(Objects.requireNonNull(project.get().getId()).getCustomerCode());
                } else {
                    return OrganisationProjectView.createFail(
                            projectUpdate.getCustomerCode(),
                            Problem.builder()
                                    .withTitle("PARENT_PROJECT_CODE_NOT_FOUND")
                                    .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }

            return OrganisationProjectView.fromEntity(projectMappingRepository.save(projectEntityUpdated));
        } else {
            return OrganisationProjectView.createFail(
                    projectUpdate.getCustomerCode(),
                    Problem.builder()
                            .withTitle("PROJECT_CODE_NOT_FOUND")
                            .withDetail("Project code with customer code %s not found.".formatted(projectUpdate.getCustomerCode()))
                            .build()
            );
        }
    }

    @Transactional
    public Either<Problem, List<OrganisationProjectView>> createProjectCodeFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ProjectUpdate.class).fold(
                Either::left,
                projectUpdates -> Either.right(projectUpdates.stream().map(projectUpdate -> insertProject(orgId, projectUpdate)).toList())
        );
    }
}
