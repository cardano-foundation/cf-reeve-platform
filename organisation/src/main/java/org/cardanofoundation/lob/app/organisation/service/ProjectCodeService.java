package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;
import org.cardanofoundation.lob.app.organisation.domain.view.ProjectView;
import org.cardanofoundation.lob.app.organisation.repository.ProjectMappingRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectCodeService {

    private final ProjectMappingRepository projectMappingRepository;
    private final CsvParser<ProjectUpdate> csvParser;

    public Optional<Project> getProject(String organisationId, String customerCode) {
        return projectMappingRepository.findById(new Project.Id(organisationId, customerCode));
    }

    public Set<Project> getAllProjects(String organisationId) {
        return projectMappingRepository.findAllByOrganisationId(organisationId);
    }

    @Transactional
    public ProjectView insertProject(String orgId, ProjectUpdate projectUpdate, boolean isUpsert) {
        Optional<Project> projectFound = getProject(orgId, projectUpdate.getCustomerCode());
        Project project = new Project();
        project.setId(new Project.Id(orgId, projectUpdate.getCustomerCode()));
        if(projectFound.isPresent()) {
            if(isUpsert) {
                project = projectFound.get();
            } else {
                return ProjectView.createFail(
                        projectUpdate,
                        Problem.builder()
                                .withTitle("PROJECT_CODE_ALREADY_EXISTS")
                                .withDetail("Project code with customer code %s already exists.".formatted(projectUpdate.getCustomerCode()))
                                .build()
                );
            }
        }
        project.setExternalCustomerCode(Optional.ofNullable(projectUpdate.getExternalCustomerCode()).orElse(projectUpdate.getCustomerCode()));
        project.setName(projectUpdate.getName());

        // check if parent exists
        if (projectUpdate.getParentCustomerCode() != null) {
            Optional<Project> parent = getProject(orgId, projectUpdate.getParentCustomerCode());
            if(parent.isPresent()) {
                project.setParentCustomerCode(Objects.requireNonNull(parent.get().getId()).getCustomerCode());
            } else {
                return ProjectView.createFail(
                        projectUpdate,
                        Problem.builder()
                                .withTitle("PARENT_PROJECT_CODE_NOT_FOUND")
                                .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                .build()
                );
            }
        }
        Project saved = projectMappingRepository.save(project);
        return ProjectView.fromEntity(saved);

    }

    @Transactional
    public ProjectView updateProject(String orgId, ProjectUpdate projectUpdate) {
        Optional<Project> projectFound = getProject(orgId, projectUpdate.getCustomerCode());
        if(projectFound.isPresent()) {
            Project projectEntityUpdated = projectFound.get();
            projectEntityUpdated.setExternalCustomerCode(projectUpdate.getExternalCustomerCode());
            projectEntityUpdated.setName(projectUpdate.getName());
            // check if parent exists
            if (projectUpdate.getParentCustomerCode() != null) {
                Optional<Project> project = getProject(orgId, projectUpdate.getParentCustomerCode());
                if(project.isPresent()) {
                    projectEntityUpdated.setParentCustomerCode(Objects.requireNonNull(project.get().getId()).getCustomerCode());
                } else {
                    return ProjectView.createFail(
                            projectUpdate,
                            Problem.builder()
                                    .withTitle("PARENT_PROJECT_CODE_NOT_FOUND")
                                    .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }

            return ProjectView.fromEntity(projectMappingRepository.save(projectEntityUpdated));
        } else {
            return ProjectView.createFail(
                    projectUpdate,
                    Problem.builder()
                            .withTitle("PROJECT_CODE_NOT_FOUND")
                            .withDetail("Project code with customer code %s not found.".formatted(projectUpdate.getCustomerCode()))
                            .build()
            );
        }
    }

    @Transactional
    public Either<Problem, List<ProjectView>> createProjectCodeFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ProjectUpdate.class).fold(
                Either::left,
                projectUpdates -> Either.right(projectUpdates.stream().map(projectUpdate -> insertProject(orgId, projectUpdate, true)).toList())
        );
    }
}
