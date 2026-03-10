package org.cardanofoundation.lob.app.organisation.service;

import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.PROJECT_MAPPINGS;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Objects;
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

    public Optional<Project> findActiveProjectById(String organisationId, String customerCode) {
        return projectRepository.findActiveProjectById(new Project.Id(organisationId, customerCode),true);
    }


    public Either<ProblemDetail, List<ProjectView>> getAllProjects(String organisationId, String customerCode, String name, String parentCustomerCode, Boolean active, Pageable pageable) {
        Either<ProblemDetail, Pageable> pageables = jpaSortFieldValidator.validateEntity(Project.class, pageable, PROJECT_MAPPINGS);
        if(pageables.isLeft()) {
            return Either.left(pageables.getLeft());
        }
        pageable = pageables.get();
        return Either.right(projectRepository.findAllByOrganisationId(organisationId, customerCode, name, parentCustomerCode, active, pageable).map(ProjectView::fromEntity).toList());
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
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Project code with customer code %s already exists.".formatted(projectUpdate.getCustomerCode()));
                problem.setTitle(ErrorTitleConstants.PROJECT_CODE_ALREADY_EXISTS);
                return ProjectView.createFail(projectUpdate, problem);
            }
        }
        project.setName(projectUpdate.getName());
        project.setActive(projectUpdate.getActive());

        // check if parent exists
        if (projectUpdate.getParentCustomerCode() != null) {
            Optional<Project> parent = getProject(orgId, projectUpdate.getParentCustomerCode());
            if(parent.isPresent()) {
                if (parent.get().getId().getCustomerCode().equals(projectUpdate.getCustomerCode())) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent project cannot be the same as the project itself: %s".formatted(projectUpdate.getCustomerCode()));
                    problem.setTitle("PARENT_PROJECT_CANNOT_BE_SELF");
                    return ProjectView.createFail(projectUpdate, problem);
                }
                if (Optional.ofNullable(parent.get().getParentCustomerCode()).orElse("").equals(projectUpdate.getCustomerCode())) {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent project with customer code %s creates a circular reference.".formatted(projectUpdate.getParentCustomerCode()));
                    problem.setTitle("CIRCULAR_REFERENCE");
                    return ProjectView.createFail(projectUpdate, problem);
                }
                project.setParentCustomerCode(Objects.requireNonNull(parent.get().getId()).getCustomerCode());
            } else {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()));
                problem.setTitle(ErrorTitleConstants.PARENT_PROJECT_CODE_NOT_FOUND);
                return ProjectView.createFail(projectUpdate, problem);
            }

        } else {
            // Unlink it
            project.setParentCustomerCode(null);
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
            projectEntityUpdated.setActive(projectUpdate.getActive());
            // check if parent exists
            if (projectUpdate.getParentCustomerCode() != null) {
                Optional<Project> parent = getProject(orgId, projectUpdate.getParentCustomerCode());
                if(parent.isPresent()) {
                    if (parent.get().getId().getCustomerCode().equals(projectUpdate.getCustomerCode())) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent project cannot be the same as the project itself: %s".formatted(projectUpdate.getCustomerCode()));
                        problem.setTitle("PARENT_PROJECT_CANNOT_BE_SELF");
                        return ProjectView.createFail(projectUpdate, problem);
                    }
                    if (Optional.ofNullable(parent.get().getParentCustomerCode()).orElse("").equals(projectUpdate.getCustomerCode())) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The parent project with customer code %s creates a circular reference.".formatted(projectUpdate.getParentCustomerCode()));
                        problem.setTitle("CIRCULAR_REFERENCE");
                        return ProjectView.createFail(projectUpdate, problem);
                    }
                    projectEntityUpdated.setParentCustomerCode(Objects.requireNonNull(parent.get().getId()).getCustomerCode());
                } else {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Parent project code with customer code %s not found.".formatted(projectUpdate.getParentCustomerCode()));
                    problem.setTitle(ErrorTitleConstants.PARENT_PROJECT_CODE_NOT_FOUND);
                    return ProjectView.createFail(projectUpdate, problem);
                }
            } else {
                // Unlink it
                projectEntityUpdated.setParentCustomerCode(null);
            }

            return ProjectView.fromEntity(projectRepository.save(projectEntityUpdated));
        } else {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Project code with customer code %s not found.".formatted(projectUpdate.getCustomerCode()));
            problem.setTitle(ErrorTitleConstants.PROJECT_CODE_NOT_FOUND);
            return ProjectView.createFail(projectUpdate, problem);
        }
    }

    @Transactional
    public Either<ProblemDetail, List<ProjectView>> createProjectCodeFromCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ProjectUpdate.class).fold(
                problemDetail -> Either.left(problemDetail),
                projectUpdates -> Either.right(projectUpdates.stream().map(projectUpdate -> {
                    Errors errors = validator.validateObject(projectUpdate);
                    List<ObjectError> allErrors = errors.getAllErrors();
                    if (!allErrors.isEmpty()) {
                        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, allErrors.stream().map(ObjectError::getDefaultMessage).collect(Collectors.joining(", ")));
                        problem.setTitle(ErrorTitleConstants.VALIDATION_ERROR);
                        return ProjectView.createFail(projectUpdate, problem);
                    }
                    return insertProject(orgId, projectUpdate, true);
                }).toList())
        );
    }

    public void downloadCsv(String orgId, String customerCode, String name, String parentCustomerCode, Boolean active, OutputStream outputStream) {
        Page<Project> allProjects = projectRepository.findAllByOrganisationId(orgId, customerCode, name, parentCustomerCode, active, Pageable.unpaged());
        try(Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            String[] header = {"Customer code", "Name", "Parent customer code", "Active"};
            csvWriter.writeNext(header, false);
            for (Project project : allProjects) {
                String[] data = {
                        project.getId().getCustomerCode(),
                        project.getName(),
                        project.getParentCustomerCode(),
                        String.valueOf(project.isActive())
                };
                csvWriter.writeNext(data, false);
            }
            csvWriter.flush();
        } catch (Exception e) {
            log.error("Error while writing currencies to CSV", e);
        }
    }
}
