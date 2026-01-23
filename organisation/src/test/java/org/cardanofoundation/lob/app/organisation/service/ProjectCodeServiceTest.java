package org.cardanofoundation.lob.app.organisation.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.cardanofoundation.lob.app.organisation.util.SortFieldMappings.PROJECT_MAPPINGS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.cardanofoundation.lob.app.organisation.domain.entity.CostCenter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.Errors;
import org.springframework.validation.ObjectError;
import org.springframework.validation.Validator;
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
import org.cardanofoundation.lob.app.organisation.repository.ProjectRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;
import org.cardanofoundation.lob.app.support.database.JpaSortFieldValidator;

@ExtendWith(MockitoExtension.class)
class ProjectCodeServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private CsvParser<ProjectUpdate> csvParser;
    @Mock
    private Validator validator;
    @Mock
    private JpaSortFieldValidator jpaSortFieldValidator;

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
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        Optional<Project> result = projectCodeService.getProject(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(project, result.get());
        verify(projectRepository).findById(projectId);
    }

    @Test
    void testGetAllProjects() {
        List<Project> projects = new ArrayList<>();
        when(projectRepository.findAllByOrganisationId("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0", null, null, null, Pageable.unpaged())).thenReturn(new PageImpl<>(projects ));
        when(jpaSortFieldValidator.validateEntity(Project.class, Pageable.unpaged(), PROJECT_MAPPINGS)).thenReturn(Either.right(Pageable.unpaged()));
        Either<Problem, List<ProjectView>> result = projectCodeService.getAllProjects("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0", null, null, null, Pageable.unpaged());
        assertTrue(result.isRight());
        assertEquals(Either.right(projects), result);
        verify(projectRepository).findAllByOrganisationId("f3b7485e96cc45b98e825a48a80d856be260b53de5fe45f23287da5b4970b9b0", null, null, null, Pageable.unpaged());
        verifyNoMoreInteractions(projectRepository);
    }

    @Test
    void testGetProject_NotFound() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        Optional<Project> result = projectCodeService.getProject(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(projectRepository).findById(projectId);
    }

    @Test
    void insertProject_AlreadyExists() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project projectMock = mock(Project.class);

        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(projectMock));

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("PROJECT_CODE_ALREADY_EXISTS", projectView.getError().get().getTitle());
    }

    @Test
    void insertProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", projectView.getError().get().getTitle());
    }

    @Test
    void insertProject_parentIsSame() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(parent.getId()).thenReturn(new Project.Id(organisationId, customerCode));
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectRepository.findById(new Project.Id(organisationId, customerCode)))
                .thenReturn(Optional.empty());
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode")))
                .thenReturn(Optional.of(parent));

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("PARENT_PROJECT_CANNOT_BE_SELF", projectView.getError().get().getTitle());
    }

    @Test
    void insertProject_cycle() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(parent.getId()).thenReturn(new Project.Id(organisationId, "parentCode"));
        when(parent.getParentCustomerCode()).thenReturn(customerCode);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectRepository.findById(new Project.Id(organisationId, customerCode)))
                .thenReturn(Optional.empty());
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode")))
                .thenReturn(Optional.of(parent));

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals("CIRCULAR_REFERENCE", projectView.getError().get().getTitle());
    }

    @Test
    void insertProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new Project.Id(organisationId, "parentCode"));
        when(projectRepository.save(any(Project.class))).thenReturn(project);

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, false);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertEquals(Optional.empty(), projectView.getError());
        assertEquals("Test Project", projectView.getName());
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void insertProject_UpsertUnlinkParent() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);

        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenReturn(project);

        project.setParentCustomerCode("parentCode");

        ProjectView projectView = projectCodeService.insertProject(organisationId, update, true);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertNull(projectView.getParentCustomerCode());
        assertEquals(Optional.empty(), projectView.getError());
        assertEquals("Test Project", projectView.getName());
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void updateProject_NotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PROJECT_CODE_NOT_FOUND", projectView.getError().get().getTitle());
    }

    @Test
    void updateProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", projectView.getError().get().getTitle());
    }

    @Test
    void updateProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        Project parent = mock(Project.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectRepository.findById(new Project.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new Project.Id(organisationId, "parentCode"));
        when(projectRepository.save(any(Project.class))).thenReturn(project);

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertEquals(Optional.empty(), projectView.getError());
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    void updateProject_unlinkParent() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);

        project.setParentCustomerCode("parentCode");

        when(projectRepository.findById(new Project.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectRepository.save(any(Project.class))).thenReturn(project);

        ProjectView projectView = projectCodeService.updateProject(organisationId, update);

        project.setParentCustomerCode(null);

        assertEquals(customerCode, projectView.getCustomerCode());
        assertEquals(Optional.empty(), projectView.getError());
        assertNull(projectView.getParentCustomerCode());
        verify(projectRepository).save(project);
    }

    @Test
    void createProjectCodeFromCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);

        when(csvParser.parseCsv(file, ProjectUpdate.class)).thenReturn(Either.left(Problem.builder().withTitle("CSV_PARSE_ERROR").build()));

        Either<Problem, List<ProjectView>> result = projectCodeService.createProjectCodeFromCsv(organisationId, file);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSE_ERROR", result.getLeft().getTitle());
    }

    @Test
    void createProjectCodeFromCsv_validationError() {
        ProjectUpdate projectUpdate = mock(ProjectUpdate.class);
        MultipartFile file = mock(MultipartFile.class);
        when(csvParser.parseCsv(file, ProjectUpdate.class)).thenReturn(Either.right(List.of(projectUpdate)));

        Errors errors = mock(Errors.class);
        ObjectError objectError = mock(ObjectError.class);
        when(validator.validateObject(projectUpdate)).thenReturn(errors);
        when(errors.getAllErrors()).thenReturn(List.of(objectError));
        when(objectError.getDefaultMessage()).thenReturn("Default Message");

        Either<Problem, List<ProjectView>> result = projectCodeService.createProjectCodeFromCsv(organisationId, file);
        assertTrue(result.isRight());
        assertEquals(1, result.get().size());
        assertNotNull(result.get().get(0).getError());
        assertEquals("Default Message", result.get().get(0).getError().get().getDetail());

    }

    @Test
    void shouldWriteCurrenciesToCsv() throws Exception {
        // given

        String orgId = "org123";
        Project project1 = mock(Project.class);
        when(project1.getId()).thenReturn(new Project.Id(organisationId, "customercode"));
        when(project1.getName()).thenReturn("Project1");
        when(project1.getParentCustomerCode()).thenReturn("Parent1");
        when(project1.isActive()).thenReturn(true);
        Project project2 = mock(Project.class);
        when(project2.getId()).thenReturn(new Project.Id(organisationId, "customercode2"));
        when(project2.getName()).thenReturn("Project2");
        when(project2.getParentCustomerCode()).thenReturn(null);
        when(project2.isActive()).thenReturn(false);

        Page<Project> page = new PageImpl<>(List.of(project1, project2));

        when(projectRepository.findAllByOrganisationId(
                any(), any(), any(), any(), any())).thenReturn(page);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        projectCodeService.downloadCsv(orgId, null, null, null, outputStream);

        // then
        String csv = outputStream.toString(StandardCharsets.UTF_8);

        String[] lines = csv.split("\\R");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("Customer code,Name,Parent customer code,Active");
        assertThat(lines[1]).isEqualTo("customercode,Project1,Parent1,true");
        assertThat(lines[2]).isEqualTo("customercode2,Project2,,false");

    }

}
