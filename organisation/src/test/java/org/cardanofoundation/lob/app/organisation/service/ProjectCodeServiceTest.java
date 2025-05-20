package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

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
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationProjectView;
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
    private OrganisationProject.Id projectId;
    private OrganisationProject project;

    @BeforeEach
    void setUp() {
        projectId = new OrganisationProject.Id(organisationId, customerCode);
        project = OrganisationProject.builder()
                .id(projectId)
                .name("Test Project")
                .build();
    }

    @Test
    void testGetProject_Found() {
        when(projectMappingRepository.findById(projectId)).thenReturn(Optional.of(project));

        Optional<OrganisationProject> result = projectCodeService.getProject(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(project, result.get());
        verify(projectMappingRepository).findById(projectId);
    }

    @Test
    void testGetProject_NotFound() {
        when(projectMappingRepository.findById(projectId)).thenReturn(Optional.empty());

        Optional<OrganisationProject> result = projectCodeService.getProject(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(projectMappingRepository).findById(projectId);
    }

    @Test
    void insertProject_AlreadyExists() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        OrganisationProject organisationProject = mock(OrganisationProject.class);

        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.of(organisationProject));

        OrganisationProjectView organisationProjectView = projectCodeService.insertProject(organisationId, update);

        assertEquals("PROJECT_CODE_ALREADY_EXISTS", organisationProjectView.getError().getTitle());
    }

    @Test
    void insertProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        OrganisationProjectView organisationProjectView = projectCodeService.insertProject(organisationId, update);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", organisationProjectView.getError().getTitle());
    }

    @Test
    void insertProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        OrganisationProject parent = mock(OrganisationProject.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.empty());
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new OrganisationProject.Id(organisationId, "parentCode"));
        when(projectMappingRepository.save(any(OrganisationProject.class))).thenReturn(project);

        OrganisationProjectView organisationProjectView = projectCodeService.insertProject(organisationId, update);

        assertEquals(customerCode, organisationProjectView.getCustomerCode());
        assertNull(organisationProjectView.getError());
        assertEquals("Test Project", organisationProjectView.getName());
        verify(projectMappingRepository).save(any(OrganisationProject.class));
    }

    @Test
    void updateProject_NotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.empty());

        OrganisationProjectView organisationProjectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PROJECT_CODE_NOT_FOUND", organisationProjectView.getError().getTitle());
    }

    @Test
    void updateProject_parentNotFound() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, "parentCode"))).thenReturn(Optional.empty());

        OrganisationProjectView organisationProjectView = projectCodeService.updateProject(organisationId, update);

        assertEquals("PARENT_PROJECT_CODE_NOT_FOUND", organisationProjectView.getError().getTitle());
    }

    @Test
    void updateProject_success() {
        ProjectUpdate update = mock(ProjectUpdate.class);
        OrganisationProject parent = mock(OrganisationProject.class);
        when(update.getCustomerCode()).thenReturn(customerCode);
        when(update.getParentCustomerCode()).thenReturn("parentCode");

        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, customerCode))).thenReturn(Optional.of(project));
        when(projectMappingRepository.findById(new OrganisationProject.Id(organisationId, "parentCode"))).thenReturn(Optional.of(parent));
        when(parent.getId()).thenReturn(new OrganisationProject.Id(organisationId, "parentCode"));
        when(projectMappingRepository.save(any(OrganisationProject.class))).thenReturn(project);

        OrganisationProjectView organisationProjectView = projectCodeService.updateProject(organisationId, update);

        assertEquals(customerCode, organisationProjectView.getCustomerCode());
        assertNull(organisationProjectView.getError());
        verify(projectMappingRepository).save(any(OrganisationProject.class));
    }

    @Test
    void createProjectCodeFromCsv_parseError() {
        MultipartFile file = mock(MultipartFile.class);

        when(csvParser.parseCsv(file, ProjectUpdate.class)).thenReturn(Either.left(Problem.builder().withTitle("CSV_PARSE_ERROR").build()));

        Either<Problem, List<OrganisationProjectView>> result = projectCodeService.createProjectCodeFromCsv(organisationId, file);

        assertTrue(result.isLeft());
        assertEquals("CSV_PARSE_ERROR", result.getLeft().getTitle());
    }

}
