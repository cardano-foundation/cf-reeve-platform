package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class MilestoneServiceTest {

    @Mock
    private MilestoneRepository milestoneRepository;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private EventMilestoneAllocationRepository allocationRepository;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private FundingCascadeDeleteService cascadeDeleteService;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    @InjectMocks
    private MilestoneService milestoneService;

    private static final Pageable PAGEABLE = PageRequest.of(0, 10);
    private static final LocalDate FUTURE_DATE = LocalDate.now().plusYears(1);

    @BeforeEach
    void allowAnyCurrencyByDefault() {
        // Currency codes referenced by these tests (USD, EUR, ...) are registered/active in the org's
        // currency table by default; tests exercising the rejection path override this per code.
        Currency activeCurrency = new Currency(new Currency.Id("org1", "x"), "ISO_4217:x", true);
        lenient().when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(any(), any()))
                .thenReturn(Optional.of(activeCurrency));
    }

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
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of(m1, m2));

        assertThat(milestoneService.findByProjectId("p1")).containsExactly(m1, m2);
    }

    @Test
    void findByProjectIdAndExternalMilestoneId_returnsEntity_whenFound() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId("p1", "MS-1")).thenReturn(Optional.of(milestone));

        assertThat(milestoneService.findByProjectIdAndExternalMilestoneId("p1", "MS-1")).contains(milestone);
    }

    @Test
    void findByProjectIdAndExternalMilestoneId_returnsEmpty_whenNotFound() {
        when(milestoneRepository.findByProjectIdAndExternalMilestoneId("p1", "MS-UNKNOWN")).thenReturn(Optional.empty());

        assertThat(milestoneService.findByProjectIdAndExternalMilestoneId("p1", "MS-UNKNOWN")).isEmpty();
    }

    @Test
    void findByProjectIdAndMilestoneTitle_delegatesToRepository() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findByProjectIdAndMilestoneTitle("p1", "Milestone AB")).thenReturn(Optional.of(milestone));

        assertThat(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone AB")).contains(milestone);
    }

    @Test
    void findByProjectId_withPageable_delegatesToRepository() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 5);
        MilestoneEntity m1 = milestoneEntity("m1");
        org.springframework.data.domain.Page<MilestoneEntity> page =
                new org.springframework.data.domain.PageImpl<>(List.of(m1));
        when(milestoneRepository.findByProjectId("p1", pageable)).thenReturn(page);

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
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of());
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
                && FUTURE_DATE.equals(m.getMilestoneDate())
                && project.equals(m.getProject())
        ));
    }

    @Test
    void create_rejected_whenCurrencyIsNotAValidIsoCode() {
        ProjectEntity project = projectEntity("p1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(any(), eq("ABC")))
                .thenReturn(Optional.empty());

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle("Milestone AB").milestoneAmount(new BigDecimal("50000.00"))
                .currency("ABC").milestoneDate(FUTURE_DATE)
                .build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.CURRENCY_INVALID);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_derivesIdFromProjectAndTitle_notFromAnyExternalId() {
        ProjectEntity project = projectEntity("p1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .externalMilestoneId("some-external-id-that-must-be-ignored")
                .milestoneTitle("Milestone AB").milestoneAmount(new BigDecimal("50000.00"))
                .currency("USD").milestoneDate(FUTURE_DATE).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getId()).isEqualTo(MilestoneEntity.id("p1", "Milestone AB"));
    }

    // -------------------------------------------------------------------------
    // resolveOrCreate — the event-allocation flow's shared "reference or create inline" path
    // -------------------------------------------------------------------------

    @Test
    void resolveOrCreate_returnsExisting_whenMilestoneTitleAlreadyExistsInProject() {
        ProjectEntity project = projectEntity("p1");
        MilestoneEntity existing = milestoneEntity("m1");
        when(milestoneRepository.findById(MilestoneEntity.id("p1", "Milestone AB"))).thenReturn(Optional.of(existing));

        // Reference-only: no creation fields supplied, just the title.
        MilestoneCreateRequest request = MilestoneCreateRequest.builder().milestoneTitle("Milestone AB").build();
        Either<ProblemDetail, MilestoneEntity> result = milestoneService.resolveOrCreate(project, request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get()).isEqualTo(existing);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void resolveOrCreate_createsNew_whenTitleDoesNotExistAndFullDataProvided() {
        ProjectEntity project = projectEntity("p1");
        when(milestoneRepository.findById(MilestoneEntity.id("p1", "New Milestone"))).thenReturn(Optional.empty());
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of());
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle("New Milestone").milestoneAmount(new BigDecimal("20000.00"))
                .currency("USD").milestoneDate(FUTURE_DATE).build();
        Either<ProblemDetail, MilestoneEntity> result = milestoneService.resolveOrCreate(project, request);

        assertThat(result.isRight()).isTrue();
        assertThat(result.get().getId()).isEqualTo(MilestoneEntity.id("p1", "New Milestone"));
        verify(milestoneRepository).saveAndFlush(any());
    }

    @Test
    void resolveOrCreate_returnsNotFound_whenTitleDoesNotExistAndCreationFieldsIncomplete() {
        ProjectEntity project = projectEntity("p1");
        when(milestoneRepository.findById(MilestoneEntity.id("p1", "Missing Milestone"))).thenReturn(Optional.empty());

        // Title supplied but no amount/currency/date — not enough to create, and nothing to resolve to.
        MilestoneCreateRequest request = MilestoneCreateRequest.builder().milestoneTitle("Missing Milestone").build();
        Either<ProblemDetail, MilestoneEntity> result = milestoneService.resolveOrCreate(project, request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void resolveOrCreate_returnsError_whenTitleMissingEntirely() {
        ProjectEntity project = projectEntity("p1");

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneAmount(new BigDecimal("20000.00")).currency("USD").milestoneDate(FUTURE_DATE).build();
        Either<ProblemDetail, MilestoneEntity> result = milestoneService.resolveOrCreate(project, request);

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED);
        verify(milestoneRepository, never()).findById(any());
    }

    @Test
    void update_returnsEmpty_whenMilestoneNotFound() {
        when(milestoneRepository.findById("m1")).thenReturn(Optional.empty());

        assertThat(milestoneService.update("m1", MilestoneUpdateRequest.builder().milestoneTitle("New").build()).isLeft()).isTrue();
    }

    @Test
    void update_updatesAmountCurrencyDate_whenProvided() {
        // milestoneTitle is immutable (the id is derived from it) — this covers every other field.
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of(milestone));
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder()
                .milestoneAmount(new BigDecimal("99000.00"))
                .currency("EUR")
                .milestoneDate(FUTURE_DATE)
                .build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(milestone.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("99000.00");
        assertThat(milestone.getCurrency()).isEqualTo("EUR");
        assertThat(milestone.getMilestoneDate()).isEqualTo(FUTURE_DATE);
    }

    @Test
    void update_success_whenMilestoneTitleResentUnchanged() {
        // Sending the same (unchanged) title back is not a "change" — it's a no-op, not rejected.
        MilestoneEntity milestone = milestoneEntity("m1"); // title "Milestone AB"
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1",
                MilestoneUpdateRequest.builder().milestoneTitle("Milestone AB").build());

        assertThat(result.isRight()).isTrue();
    }

    @Test
    void update_returnsError_whenMilestoneTitleChanged() {
        // milestoneTitle is immutable — the milestone's id is derived from it. Attempting to change it
        // is rejected outright, regardless of whether the new title would itself conflict.
        MilestoneEntity milestone = milestoneEntity("m1"); // title "Milestone AB"
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1",
                MilestoneUpdateRequest.builder().milestoneTitle("Renamed").build());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_TITLE_IMMUTABLE);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsConflict_whenLinkedToPublishedEvent() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(true);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", MilestoneUpdateRequest.builder().milestoneTitle("New").build());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo("SPENDING_EVENT_ALREADY_PUBLISHED");
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsConflict_whenTitleExistsInProject() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneRepository.existsByProjectIdAndMilestoneTitle("p1", "Milestone AB")).thenReturn(true);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", createRequest());

        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_TITLE_ALREADY_EXISTS);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_skipsNullFields() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().build();
        milestoneService.update("m1", request);

        assertThat(milestone.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("50000.00");
        assertThat(milestone.getCurrency()).isEqualTo("USD");
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

        assertThat(view.getMilestoneId()).isEqualTo("m1");
        assertThat(view.getMilestoneTitle()).isEqualTo("Milestone AB");
        assertThat(view.getMilestoneAmount()).isEqualByComparingTo("50000.00");
        assertThat(view.getCurrency()).isEqualTo("USD");
        assertThat(view.getMilestoneDate()).isEqualTo(LocalDate.of(2025, 6, 30));
    }

    @Test
    void toView_setsCalculatedSpentAmount() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(allocationRepository.spentAmountByMilestoneId("m1", EventType.SPENDING))
                .thenReturn(new BigDecimal("12000.00"));

        MilestoneView view = milestoneService.toView(milestone);

        assertThat(view.getSpentAmount()).isEqualByComparingTo("12000.00");
    }

    // -------------------------------------------------------------------------
    // View-returning API + org-access authorisation (authorizeProject)
    // -------------------------------------------------------------------------

    @Test
    void listMilestones_returns404_whenProjectNotFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.empty());

        PagedResponse<MilestoneView> result = milestoneService.listMilestones("p1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_NOT_FOUND);
        verify(milestoneRepository, never()).findByProjectId(any(), any());
    }

    @Test
    void listMilestones_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        PagedResponse<MilestoneView> result = milestoneService.listMilestones("p1", PAGEABLE);

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(milestoneRepository, never()).findByProjectId(any(), any());
    }

    @Test
    void listMilestones_returnsPage_whenAuthorised() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByProjectId("p1", PAGEABLE))
                .thenReturn(new PageImpl<>(List.of(milestoneEntity("m1"))));

        PagedResponse<MilestoneView> result = milestoneService.listMilestones("p1", PAGEABLE);

        assertThat(result.getError()).isEmpty();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getMilestone_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        MilestoneView result = milestoneService.getMilestone("p1", "m1");

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(milestoneRepository, never()).findByIdAndProjectId(any(), any());
    }

    @Test
    void getMilestone_returns404_whenMilestoneNotInProject() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        MilestoneView result = milestoneService.getMilestone("p1", "m1");

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    @Test
    void getMilestone_returnsView_whenFound() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestoneEntity("m1")));

        MilestoneView result = milestoneService.getMilestone("p1", "m1");

        assertThat(result.getError()).isEmpty();
        assertThat(result.getMilestoneId()).isEqualTo("m1");
    }

    @Test
    void createMilestone_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        MilestoneView result = milestoneService.createMilestone("p1", createRequest());

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void createMilestone_returnsView_whenAuthorisedAndCreated() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.saveAndFlush(any())).thenReturn(milestoneEntity("m-new"));

        MilestoneView result = milestoneService.createMilestone("p1", createRequest());

        assertThat(result.getError()).isEmpty();
        assertThat(result.getMilestoneId()).isEqualTo("m-new");
    }

    @Test
    void updateMilestone_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        MilestoneView result = milestoneService.updateMilestone("p1", "m1",
                MilestoneUpdateRequest.builder().milestoneTitle("New").build());

        assertThat(result.getError().orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateMilestone_returns404_whenMilestoneNotInProject() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        MilestoneView result = milestoneService.updateMilestone("p1", "m1",
                MilestoneUpdateRequest.builder().milestoneTitle("New").build());

        assertThat(result.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteMilestone_returns401_whenUserCannotAccessOrg() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(false);

        Optional<ProblemDetail> result = milestoneService.deleteMilestone("p1", "m1");

        assertThat(result.orElseThrow().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(cascadeDeleteService, never()).deleteMilestone(any());
    }

    @Test
    void deleteMilestone_returns404_whenMilestoneNotInProject() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.empty());

        Optional<ProblemDetail> result = milestoneService.deleteMilestone("p1", "m1");

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_FOUND);
        verify(cascadeDeleteService, never()).deleteMilestone(any());
    }

    @Test
    void deleteMilestone_delegatesToCascade_whenAuthorised() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestone));
        when(cascadeDeleteService.deleteMilestone(milestone)).thenReturn(Optional.empty());

        Optional<ProblemDetail> result = milestoneService.deleteMilestone("p1", "m1");

        assertThat(result).isEmpty();
        verify(cascadeDeleteService).deleteMilestone(milestone);
    }

    @Test
    void deleteMilestone_propagatesConflict_fromCascade() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(keycloakSecurityHelper.canUserAccessOrg("org1")).thenReturn(true);
        when(milestoneRepository.findByIdAndProjectId("m1", "p1")).thenReturn(Optional.of(milestone));
        ProblemDetail conflict = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        conflict.setTitle(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
        when(cascadeDeleteService.deleteMilestone(milestone)).thenReturn(Optional.of(conflict));

        Optional<ProblemDetail> result = milestoneService.deleteMilestone("p1", "m1");

        assertThat(result.orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    // -------------------------------------------------------------------------
    // Amount / date business validations (wired via FundingValidations)
    // -------------------------------------------------------------------------

    @Test
    void create_acceptsMilestoneDateInPast() {
        // Historic data may be recorded — past milestone dates are allowed.
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(milestoneRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle("MS").milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                .milestoneDate(LocalDate.now().minusDays(1)).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.isRight()).isTrue();
        verify(milestoneRepository).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenAmountExceedsProjectTotal() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1"))); // total 200000

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle("MS").milestoneAmount(new BigDecimal("250000.00")).currency("USD")
                .milestoneDate(FUTURE_DATE).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_EXCEEDS_PROJECT);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_returnsLeft_whenCumulativeMilestonesExceedProjectTotal() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1"))); // total 200000
        MilestoneEntity existing = MilestoneEntity.builder().id("m-existing").milestoneAmount(new BigDecimal("180000.00")).build();
        when(milestoneRepository.findByProjectId("p1")).thenReturn(List.of(existing));

        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle("MS").milestoneAmount(new BigDecimal("50000.00")).currency("USD")
                .milestoneDate(FUTURE_DATE).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_TOTAL_EXCEEDS_PROJECT);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_returnsLeft_whenNewAmountBelowTotalAllocated() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(allocationRepository.sumAllocatedByMilestoneId("m1")).thenReturn(new BigDecimal("60000.00"));

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneAmount(new BigDecimal("50000.00")).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", request);

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_AMOUNT_BELOW_ALLOCATED);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_succeeds_whenNewAmountEqualsTotalAllocated() {
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(allocationRepository.sumAllocatedByMilestoneId("m1")).thenReturn(new BigDecimal("60000.00"));
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneAmount(new BigDecimal("60000.00")).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(milestone.getMilestoneAmount()).isEqualByComparingTo("60000.00");
    }

    @Test
    void update_acceptsDateInPast() {
        // Historic data may be recorded — past milestone dates are allowed.
        MilestoneEntity milestone = milestoneEntity("m1");
        when(milestoneRepository.findById("m1")).thenReturn(Optional.of(milestone));
        when(allocationRepository.existsByMilestoneIdAndEventStatus("m1", EventStatus.PUBLISHED)).thenReturn(false);
        when(milestoneRepository.saveAndFlush(milestone)).thenReturn(milestone);

        LocalDate pastDate = LocalDate.now().minusDays(1);
        MilestoneUpdateRequest request = MilestoneUpdateRequest.builder().milestoneDate(pastDate).build();

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.update("m1", request);

        assertThat(result.isRight()).isTrue();
        assertThat(milestone.getMilestoneDate()).isEqualTo(pastDate);
    }

    @Test
    void create_returnsLeft_whenProjectHasSubProjects() {
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1")));
        when(projectRepository.existsByParentProjectId("p1")).thenReturn(true);

        Either<ProblemDetail, MilestoneEntity> result = milestoneService.create("p1", createRequest());

        assertThat(result.getLeft().getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS);
        verify(milestoneRepository, never()).saveAndFlush(any());
    }

    @Test
    void hasMilestones_delegatesToRepository() {
        when(milestoneRepository.existsByProjectId("p1")).thenReturn(true);

        assertThat(milestoneService.hasMilestones("p1")).isTrue();
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
                .externalProjectId("PROJ-AB")
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
                .milestoneDate(FUTURE_DATE)
                .build();
    }

}
