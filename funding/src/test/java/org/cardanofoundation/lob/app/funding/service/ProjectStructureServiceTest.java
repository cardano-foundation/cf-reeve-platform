package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        lenient().when(milestoneService.isCurrencyRegisteredAndActive(any(), any())).thenReturn(true);
        lenient().when(projectRepository.findByParentProjectId(anyString())).thenReturn(List.of());
        lenient().when(projectRepository.saveAndFlush(any(ProjectEntity.class)))
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

    @ParameterizedTest(name = "sub-project currency \"{0}\" resolves to \"{1}\"")
    @CsvSource(nullValues = "NULL", value = {
            "EUR,  EUR",
            "NULL, USD",
            "'  ', USD",
    })
    void subProjectCurrency_resolvesToExpectedValue(String givenCurrency, String expectedCurrency) {
        ProjectEntity parent = root("USD");

        Either<ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), givenCurrency);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getCurrency()).isEqualTo(expectedCurrency);
    }

    @Test
    void whenCreated_isDeterministicSubIdOfParent() {
        ProjectEntity parent = root("USD");

        Either<ProblemDetail, ProjectEntity> result = projectStructureService.createSubProject(
                parent, "Sub One", null, new BigDecimal("40000.00"), null);

        assertThat(result.get().getId()).isEqualTo(ProjectEntity.subId(parent.getId(), "Sub One"));
        assertThat(result.get().getParentProject()).isEqualTo(parent);
    }
}
