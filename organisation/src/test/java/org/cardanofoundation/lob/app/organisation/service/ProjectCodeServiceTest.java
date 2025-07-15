package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;
import org.cardanofoundation.lob.app.organisation.domain.view.ProjectView;
import org.cardanofoundation.lob.app.organisation.repository.ProjectMappingRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class ProjectCodeServiceTest {

    @Mock
    private ProjectMappingRepository projectMappingRepository;
    @Mock
    private CsvParser<ProjectUpdate> csvParser;

    @InjectMocks
    private ProjectCodeService projectCodeService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private Project.Id projectId;
    private Project project;

    @BeforeEach
    void setUp() {
        projectId = new Project.Id(organisationId, customerCode);
        project = Project.builder()
                .id(projectId)
                .name("Test Project")
                .build();
    }

    @Test
    void testGetProject_Found() {
        when(projectMappingRepository.findById(projectId)).thenReturn(Optional.of(project));

        Optional<Project> result = projectCodeService.getProject(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(project, result.get());
        verify(projectMappingRepository).findById(projectId);
    }

    @Test
    void testGetAllProjects() {
        Set<Project> projects = new HashSet<>();
        when(projectMappingRepository.findAllByOrganisationId("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0")).thenReturn(projects);
        Set<Project> result = projectCodeService.getAllProjects("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0");
        assertEquals(projects, result);
        verify(projectMappingRepository).findAllByOrganisationId("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0");
        verifyNoMoreInteractions(projectMappingRepository);
    }

    @Test
    void testGetProject_NotFound() {
        when(projectMappingRepository.findById(projectId)).thenReturn(Optional.empty());

        Optional<Project> result = projectCodeService.getProject(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(projectMappingRepository).findById(projectId);
    }

    @Test
    void insertProject_AlreadyExists() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project project = mock(Project.class);

        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("PROJECT_CODE_ALREADY_EXISTS", projectView.getError().getTitle());
    }

    @Test
    void insertProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectMappingRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", projectView.getError().getTitle());
    }

    @Test
    void insertProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectMappingRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new Project.Id(organisationId, "parentCode"));
        when(projectMappingRepository.save(any(Project.class))).thenReturn(project);

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertNull(projectView.getError());
        assertEquals("Test Project", projectView.getName());
        verify(projectMappingRepository).save(any(Project.class));
    }

    @Test
    void updateProject_NotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PROJECT_CODE_NOT_FOUND", projectView.getError().getTitle());
    }

    @Test
    void updateProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectMappingRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", projectView.getError().getTitle());
    }

    @Test
    void updateProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectMappingRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectMappingRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new Project.Id(organisationId, "parentCode"));
        when(projectMappingRepository.save(any(Project.class))).thenReturn(project);

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertNull(projectView.getError());
        verify(projectMappingRepository).save(any(Project.class));
    }

    @Test
    void createProjectCodeFromCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);

        when(csvParser.parseCsv(file, ProjectUpdate.class)).thenReturn(Either.left(Problem.builder().withTitle("CSV_PARSE_ERROR").build()));

        Either<Problem, List<ProjectView>> result = projectCodeService.createProjectCodeFromCsv(organisationId, file);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSE_ERROR", result.getLeft().getTitle());
    }

}
