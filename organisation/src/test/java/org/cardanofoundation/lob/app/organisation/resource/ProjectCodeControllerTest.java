package org.cardanofoundation.lob.app.organisation.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;
import org.cardanofoundation.lob.app.organisation.domain.view.OrganisationProjectView;
import org.cardanofoundation.lob.app.organisation.service.ProjectCodeService;

@ExtendWith(MockitoExtension.class)
class ProjectCodeControllerTest {

    @Mock
    private ProjectCodeService projectCodeService;

    @InjectMocks
    private ProjectCodeController projectCodeController;

    @Test
    void getAllProjects() {
        // Mock the service call
        when(projectCodeService.getAllProjects("org123")).thenReturn(Set.of(mock(OrganisationProject.class)));

        // Call the controller method
        ResponseEntity<Set<OrganisationProjectView>> response = projectCodeController.getAllProjects("org123");

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void insertProject() {
        OrganisationProjectView projectView = mock(OrganisationProjectView.class);
        ProjectUpdate projectUpdate = mock(ProjectUpdate.class);
        // Mock the service call
        when(projectCodeService.insertProject("org123", projectUpdate)).thenReturn(projectView);

        // Call the controller method
        ResponseEntity<OrganisationProjectView> response = projectCodeController.insertProject("org123", projectUpdate);

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(projectView, response.getBody());
    }

    @Test
    void updateProject() {
        OrganisationProjectView projectView = mock(OrganisationProjectView.class);
        ProjectUpdate projectUpdate = mock(ProjectUpdate.class);
        // Mock the service call
        when(projectCodeService.updateProject("org123", projectUpdate)).thenReturn(projectView);

        // Call the controller method
        ResponseEntity<OrganisationProjectView> response = projectCodeController.updateProject("org123", projectUpdate);

        // Verify the response
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        assertEquals(projectView, response.getBody());
    }

    @Test
    void insertProjectsCsv_error() {
        MultipartFile file = mock(MultipartFile.class);

        // Mock the service call
        when(projectCodeService.createProjectCodeFromCsv("org123", file)).thenReturn(Either.left(Problem.valueOf(Status.BAD_REQUEST)));

        ResponseEntity<?> responseEntity = projectCodeController.insertProjectsCsv("org123", file);

        // Verify the response
        assertEquals(Status.BAD_REQUEST.getStatusCode(), responseEntity.getStatusCode().value());
        assertNotNull(responseEntity.getBody());
    }

    @Test
    void insertProjectCsv_success() {
        MultipartFile file = mock(MultipartFile.class);
        OrganisationProjectView projectView = mock(OrganisationProjectView.class);

        // Mock the service call
        when(projectCodeService.createProjectCodeFromCsv("org123", file)).thenReturn(Either.right(List.of(projectView)));

        ResponseEntity<?> responseEntity = projectCodeController.insertProjectsCsv("org123", file);

        // Verify the response
        assertTrue(responseEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(responseEntity.getBody());
        assertEquals(List.of(projectView), responseEntity.getBody());
        assertEquals(200, responseEntity.getStatusCode().value());
    }
}
