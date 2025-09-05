package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.PROJECT_MAPPINGS;

import java.util.List;
import java.util.Objects;
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

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;
import org.cardanofoundation.lob.app.organisation.domain.view.ProjectView;
import org.cardanofoundation.lob.app.organisation.repository.ProjectRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.organisation.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectCodeService {

    private final ProjectRepository projectRepository;
    private final CsvParser<ProjectUpdate> csvParser;
    private final Validator validator;
    private final JpaSortFieldValidator jpaSortFieldValidator;

    public Optional<Project> getProject(String organisationId, String customerCode) {
        return projectRepository.findById(new Project.Id(organisationId, customerCode));
    }

    public Either<Problem, List<ProjectView>> getAllProjects(String organisationId, String customerCode, String name, String parentCustomerCode, Pageable pageable) {
        Either<Problem, Pageable> pageables = jpaSortFieldValidator.validateEntity(Project.class, pageable, PROJECT_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        return Either.right(projectRepository.findAllByOrganisationId(organisationId, customerCode, name, parentCustomerCode, pageable).map(ProjectView::fromEntity).toList());
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
                                .withStatus(Status.CONFLICT)
                                .withTitle(ErrorTitleConstants.PROJECT_CODE_ALREADY_EXISTS)
                                .withDetail("Project code with customer code %s already exists.".formatted(projectUpdate.getCustomerCode()))
                                .build()
                );
            }
        }
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
                                .withStatus(Status.NOT_FOUND)
                                .withTitle(ErrorTitleConstants.PARENT_PROJECT_CODE_NOT_FOUND)
                                .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                .build()
                );
            }
        }
        Project saved = projectRepository.save(project);
        return ProjectView.fromEntity(saved);

    }

    @Transactional
    public ProjectView updateProject(String orgId, ProjectUpdate projectUpdate) {
        Optional<Project> projectFound = getProject(orgId, projectUpdate.getCustomerCode());
        if(projectFound.isPresent()) {
            Project projectEntityUpdated = projectFound.get();
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
                                    .withStatus(Status.NOT_FOUND)
                                    .withTitle(ErrorTitleConstants.PARENT_PROJECT_CODE_NOT_FOUND)
                                    .withDetail("Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()))
                                    .build()
                    );
                }
            }

            return ProjectView.fromEntity(projectRepository.save(projectEntityUpdated));
        } else {
            return ProjectView.createFail(
                    projectUpdate,
                    Problem.builder()
                            .withStatus(Status.NOT_FOUND)
                            .withTitle(ErrorTitleConstants.PROJECT_CODE_NOT_FOUND)
                            .withDetail("Project code with customer code %s not found.".formatted(projectUpdate.getCustomerCode()))
                            .build()
            );
        }
    }

    @Transactional
    public Either<Problem, List<ProjectView>> createProjectCodeFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ProjectUpdate.class).fold(
                Either::left,
                projectUpdates -> Either.right(projectUpdates.stream().map(projectUpdate -> {
                    Errors errors = validator.validateObject(projectUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        return ProjectView.createFail(projectUpdate, Problem.builder()
                                .withTitle(ErrorTitleConstants.VALIDATION_ERROR)
                                .withDetail(allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")))
                                .withStatus(Status.BAD_REQUEST)
                                .build());
                    }
                    return insertProject(orgId, projectUpdate, true);
                }).toList())
        );
    }
}
