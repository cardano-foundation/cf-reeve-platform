package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationProject;
import org.cardanofoundation.lob.app.organisation.repository.ProjectMappingRepository;

@ExtendWith(MockitoExtension.class)
class ProjectCodeServiceTest {

    @Mock
    private ProjectMappingRepository projectMappingRepository;

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
}
