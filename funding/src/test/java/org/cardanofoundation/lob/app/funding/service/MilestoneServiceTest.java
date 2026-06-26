package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;

@ExtendWith(MockitoExtension.class)
class MilestoneServiceTest {

    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;

    @InjectMocks
    private MilestoneService milestoneService;

    @Test
    void findById_returnsEmpty_whenNotFound() {
        when(milestoneRepository.findById("m1")).thenReturn(Optional.empty());

        assertThat(milestoneService.findById("m1")).isEmpty();
    }

    @Test
    void findById_returnsEntity_whenFound() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));

        assertThat(milestoneService.findById("m1")).contains(milestone);
    }

    @Test
    void findByProjectId_returnsList() {
        MilestoneEntity m1 = milestoneEntity("m1");
        MilestoneEntity m2 = milestoneEntity("m2");
        when(milestoneRepository.findByProject_Id("p1")).thenReturn(List.of(m1, m2));

        assertThat(milestoneService.findByProjectId("p1")).containsExactly(m1, m2);
    }

    @Test
    void findByProjectId_withPageable_delegatesToRepository() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 5);
        MilestoneEntity m1 = milestoneEntity("m1");
        org.springframework.data.domain.Page<MilestoneEntity> page =
                new org.springframework.data.domain.PageImpl<>(List.of(m1));
        when(milestoneRepository.findByProject_Id("p1", pageable)).thenReturn(page);

        assertThat(milestoneService.findByProjectId("p1", pageable)).isEqualTo(page);
    }

    @Test
    void create_returnsLeft_whenMissingRequiredFields() {
        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle(null)
                .milestoneAmount(null)
                .currency(null)
                .milestoneDate(null)
                .build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("MILESTONE_FIELDS_REQUIRED");
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsEmpty_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        MilestoneCreateRequest request = createRequest();
        assertThat(milestoneService.create("p1", request).isLeft()).isTrue();
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_savesAndReturnsMilestone_whenProjectExists() {
        ProjectEntity project = projectEntity("p1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        MilestoneEntity saved = milestoneEntity("m-new");
        when(milestoneRepository.saveAndFlush(any())).thenReturn(saved);

        MilestoneCreateRequest request = createRequest();
        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo(saved);
        verify(milestoneRepository).saveAndFlush(argThat(m ->
                "Milestone AB".equals(m.getMilestoneTitle())
                && new BigDecimal("50000.00").equals(m.getMilestoneAmount())
                && "USD".equals(m.getCurrency())
                && LocalDate.of(2025, 6, 30).equals(m.getMilestoneDate())
                && project.equals(m.getProject())
        ));
    }

    @Test
    void update_returnsEmpty_whenMilestoneNotFound() {
        when(milestoneRepository.findById("m1")).thenReturn(Optional.empty());

        assertThat(milestoneService.update("m1", MilestoneUpdateRequest.builder().milestoneTitle("New").build()).isLeft()).isTrue();
    }

    @Test
    void update_updatesAllFields_whenAllProvided() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestone_IdAndEvent_Status("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder()
                .milestoneTitle("Updated Label")
                .milestoneAmount(new BigDecimal("99000.00"))
                .currency("EUR")
                .milestoneDate(LocalDate.of(2026, 1, 1))
                .build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(milestone.getMilestoneTitle()).isEqualTo("Updated Label");
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("99000.00");
        assertThat(milestone.getCurrency()).isEqualTo("EUR");
        assertThat(milestone.getMilestoneDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void update_returnsConflict_whenLinkedToPublishedEvent() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestone_IdAndEvent_Status("m1", EventStatus.PUBLISHED)).thenReturn(true);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", MilestoneUpdateRequest.builder().milestoneTitle("New").build());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_skipsNullFields() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestone_IdAndEvent_Status("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().build();
        milestoneService.update("m1", request);

        assertThat(milestone.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("50000.00");
        assertThat(milestone.getCurrency()).isEqualTo("USD");
    }

    @Test
    void delete_returnsFalse_whenNotFound() {
        when(milestoneRepository.existsById("m1")).thenReturn(false);

        assertThat(milestoneService.delete("m1").isLeft()).isTrue();
        verify(milestoneRepository, never()).deleteById(any());
    }

    @Test
    void delete_deletesAndReturnsTrue_whenFound() {
        when(milestoneRepository.existsById("m1")).thenReturn(true);
        when(allocationRepository.existsByMilestone_IdAndEvent_Status("m1", EventStatus.PUBLISHED)).thenReturn(false);

        assertThat(milestoneService.delete("m1").isRight()).isTrue();
        verify(milestoneRepository).deleteById("m1");
    }

    @Test
    void delete_returnsConflict_whenLinkedToPublishedEvent() {
        when(milestoneRepository.existsById("m1")).thenReturn(true);
        when(allocationRepository.existsByMilestone_IdAndEvent_Status("m1", EventStatus.PUBLISHED)).thenReturn(true);

        Either<ProblemDetail, Void> result = milestoneService.delete("m1");

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(milestoneRepository, never()).deleteById(any());
    }

    @Test
    void belongsToProject_returnsTrue_whenProjectMatches() {
        ProjectEntity project = projectEntity("p1");
        MilestoneEntity milestone = milestoneEntity("m1");
        milestone.setProject(project);

        assertThat(milestoneService.belongsToProject(milestone, project)).isTrue();
    }

    @Test
    void belongsToProject_returnsFalse_whenProjectDiffers() {
        ProjectEntity project1 = projectEntity("p1");
        ProjectEntity project2 = projectEntity("p2");
        MilestoneEntity milestone = milestoneEntity("m1");
        milestone.setProject(project1);

        assertThat(milestoneService.belongsToProject(milestone, project2)).isFalse();
    }

    @Test
    void toView_mapsAllFields() {
        MilestoneEntity milestone = milestoneEntity("m1");

        MilestoneView view = milestoneService.toView(milestone);

        assertThat(view.getMilestoneUid()).isEqualTo("m1");
        assertThat(view.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(view.getMilestoneAmount()).isEqualByComparingTo("50000.00");
        assertThat(view.getCurrency()).isEqualTo("USD");
        assertThat(view.getMilestoneDate()).isEqualTo(LocalDate.of(2025, 6, 30));
    }

    // --- helpers ---

    private MilestoneEntity milestoneEntity(String id) {
        ProjectEntity project = projectEntity("p1");
        return MilestoneEntity.builder()
                .id(id)
                .milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30))
                .project(project)
                .build();
    }

    private ProjectEntity projectEntity(String id) {
        return ProjectEntity.builder()
                .id(id)
                .organisationId("org1")
                .fundingId("GRANT-2025-001")
                .projectId("PROJ-AB")
                .projectTitle("Project AB")
                .totalAmount(new BigDecimal("200000.00"))
                .currency("USD")
                .build();
    }

    private MilestoneCreateRequest createRequest() {
        return MilestoneCreateRequest.builder()
                .milestoneTitle("Milestone AB")
                .milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD")
                .milestoneDate(LocalDate.of(2025, 6, 30))
                .build();
    }

}
