package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.util.List;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

@ExtendWith(MockitoExtension.class)
class ProjectStructureServiceTest {

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneService milestoneService;

    private ProjectStructureService projectStructureService;

    @BeforeEach
    void setUp() {
        projectStructureService = new ProjectStructureService(projectRepository, milestoneService);
        lenient().when(milestoneService.hasMilestones(anyString())).thenReturn(false);
        lenient().when(projectRepository.findByParentProjectId(anyString())).thenReturn(List.of());
        lenient().when(projectRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(ProjectEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ProjectEntity root(String currency) {
        return ProjectEntity.builder()
                .id("root-id")
                .organisationId("org1")
                .projectTitle("Root")
                .totalAmount(new BigDecimal("100000.00"))
                .currency(currency)
                .build();
    }

    @Test
    void subProjectCurrency_explicitlyGiven_isUsedAsIs() {
        ProjectEntity parent = root("USD");

        Either<org.springframework.http.ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), "EUR");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void subProjectCurrency_null_defaultsToParentCurrency() {
        ProjectEntity parent = root("USD");

        Either<org.springframework.http.ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), null);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getCurrency()).isEqualTo("USD");
    }

    @Test
    void subProjectCurrency_blank_defaultsToParentCurrency() {
        ProjectEntity parent = root("USD");

        Either<org.springframework.http.ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), "  ");

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getCurrency()).isEqualTo("USD");
    }

    @Test
    void whenCreated_isDeterministicSubIdOfParent() {
        ProjectEntity parent = root("USD");

        Either<org.springframework.http.ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), null);

        assertThat(result.get().getId()).isEqualTo(ProjectEntity.subId(parent.getId(), "Sub One"));
        assertThat(result.get().getParentProject()).isEqualTo(parent);
    }
}
