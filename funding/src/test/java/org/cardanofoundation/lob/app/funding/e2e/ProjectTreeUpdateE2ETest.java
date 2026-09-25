package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventProjectAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventSubProjectAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.domain.view.OrphanEventsCleanupView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.funding.service.ProjectTreeUpdateService;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Real end-to-end reproduction, against a real Postgres, of the "Project Atlas" delete-then-orphan
 * walkthrough (see the SpendingEventController/ProjectController OpenAPI examples) — going through the
 * actual, Spring-proxied service chain (ProjectService, SpendingEventService, ProjectTreeUpdateService),
 * not raw repository saves, to reproduce whatever real object-graph state a live request builds up.
 * Written to chase a reported TransientObjectException on step 3 (update Sub A + delete Sub B in the
 * same PUT), which a mocked unit test cannot reproduce (Hibernate flush behavior is exactly what's in
 * question).
 */
@SpringBootTest(classes = ProjectTreeUpdateE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProjectTreeUpdateE2ETest {

    private static final String ORG_ID = "org-atlas-e2e";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.3");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgresql/common");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectTreeUpdateService projectTreeUpdateService;
    @Autowired
    private SpendingEventService spendingEventService;
    @Autowired
    private FundingEventRepository fundingEventRepository;
    @Autowired
    private EventMilestoneAllocationRepository allocationRepository;
    @Autowired
    private FundingProjectRepository projectRepository;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @Test
    void updatingSubA_andDeletingSubB_inTheSamePut_doesNotThrow() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
        Currency activeCurrency = new Currency(new Currency.Id(ORG_ID, "x"), "ISO_4217:x", true);
        lenient().when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(anyString(), anyString()))
                .thenReturn(Optional.of(activeCurrency));

        // Step 1: create "Project Atlas" with Sub A / Milestone A1 and Sub B / Milestone B1. The
        // create-tree endpoint always auto-assigns a sub-project's/milestone's proId regardless of what
        // (if anything) is supplied here, so these are matched by title alone later on, like a real UI
        // would (it never sees a sub-project/milestone proId either — see LOB-2366/2384).
        ProjectView created = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-ATLAS").projectTitle("Project Atlas")
                .proId("PRJ-2000-E2E").totalAmount(new BigDecimal("80000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone A1").milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 15)).build()))
                                .build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS-B").projectTitle("Sub B")
                                .totalAmount(new BigDecimal("30000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone B1").milestoneAmount(new BigDecimal("30000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 20)).build()))
                                .build()))
                .build());
        assertThat(created.getError()).isEmpty();
        String rootId = created.getProjectId();

        // Step 2a: FUNDING event allocating to both Sub A's and Sub B's milestones.
        SpendingEventView funding = spendingEventService.createEvent(SpendingEventCreateRequest.builder()
                .organisationId(ORG_ID).eventType(EventType.FUNDING).fundingId("GRANT-ATLAS-E2E-1")
                .fundingHash("atlas-e2e-hash-1").fundingEntity("Cardano Foundation").currencyRcy("ADA")
                .eventDate(LocalDate.of(2026, 9, 15)).amountRcy(new BigDecimal("35000.00"))
                .allocations(List.of(EventProjectAllocationRequest.builder().projectTitle("Project Atlas")
                        .subProjects(List.of(
                                EventSubProjectAllocationRequest.builder().projectTitle("Sub A")
                                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                                .milestone(MilestoneCreateRequest.builder().milestoneTitle("Milestone A1").build())
                                                .allocatedAmount(new BigDecimal("20000.00")).build()))
                                        .build(),
                                EventSubProjectAllocationRequest.builder().projectTitle("Sub B")
                                        .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                                .milestone(MilestoneCreateRequest.builder().milestoneTitle("Milestone B1").build())
                                                .allocatedAmount(new BigDecimal("15000.00")).build()))
                                        .build()))
                        .build()))
                .build());
        assertThat(funding.getError()).isEmpty();

        // Step 2b: SPENDING event allocating only to Sub B's milestone.
        SpendingEventView spending = spendingEventService.createEvent(SpendingEventCreateRequest.builder()
                .organisationId(ORG_ID).eventType(EventType.SPENDING).fundingId("GRANT-ATLAS-E2E-2")
                .fundingHash("atlas-e2e-hash-2").currencyRcy("ADA").eventDate(LocalDate.of(2026, 9, 20))
                .category("Personnel").vendor("Vendor Atlas")
                .amountFcy(new BigDecimal("10000.00")).currencyFcy("USD").fxRate(new BigDecimal("1.0"))
                .amountRcy(new BigDecimal("10000.00")).hash("sha256:demo-atlas-e2e-2").notes("Invoice E2E-2")
                .allocations(List.of(EventProjectAllocationRequest.builder().projectTitle("Project Atlas")
                        .subProjects(List.of(EventSubProjectAllocationRequest.builder().projectTitle("Sub B")
                                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                        .milestone(MilestoneCreateRequest.builder().milestoneTitle("Milestone B1").build())
                                        .allocatedAmount(new BigDecimal("10000.00")).build()))
                                .build()))
                        .build()))
                .build());
        assertThat(spending.getError()).isEmpty();

        // Step 3: update Sub A (resent unchanged) + delete Sub B, in the same PUT — this is what the
        // bug report's payload does.
        ProjectView updated = projectTreeUpdateService.updateWithMilestones(rootId, ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-ATLAS").projectTitle("Project Atlas")
                .proId("PRJ-2000-E2E").totalAmount(new BigDecimal("50000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone A1").milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 15)).build()))
                                .build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS-B").projectTitle("Sub B")
                                .action("DELETE").build()))
                .build());

        assertThat(updated.getError()).isEmpty();
        assertThat(updated.getAffectedEvents()).hasSize(2);

        FundingEventEntity fundingReloaded = fundingEventRepository.findById(funding.getEventId()).orElseThrow();
        FundingEventEntity spendingReloaded = fundingEventRepository.findById(spending.getEventId()).orElseThrow();
        assertThat(fundingReloaded.getStatus()).isEqualTo(EventStatus.ERROR);
        assertThat(spendingReloaded.getStatus()).isEqualTo(EventStatus.ERROR);

        // What the UI actually does next: open each ERROR event. Its allocation to the deleted Milestone
        // B1 is still there, dangling — building the view for it must not blow up.
        SpendingEventView fundingView = spendingEventService.getEvent(funding.getEventId());
        SpendingEventView spendingView = spendingEventService.getEvent(spending.getEventId());
        assertThat(fundingView.getError()).isEmpty();
        assertThat(spendingView.getError()).isEmpty();

        // The SPENDING event only ever allocated to Sub B, so nothing is left under a live project — but
        // its allocation row was not deleted, and must still show up rather than silently vanish.
        // Also mirrored into projectAllocations as one project-less placeholder entry.
        assertThat(spendingView.getProjectAllocations()).hasSize(1);
        assertThat(spendingView.getProjectAllocations().get(0).isContainsDeletedMilestones()).isTrue();
        assertThat(spendingView.getProjectAllocations().get(0).getProjectId()).isNull();
        assertThat(spendingView.getProjectAllocations().get(0).getMilestoneAllocations()).hasSize(1);
        assertThat(spendingView.getProjectAllocations().get(0).getMilestoneAllocations().get(0).isMilestoneDeleted()).isTrue();
        assertThat(spendingView.getProjectAllocations().get(0).getMilestoneAllocations().get(0).getAllocatedAmount())
                .isEqualByComparingTo("10000.00");
        assertThat(spendingView.getOrphanedAllocations()).hasSize(1);
        assertThat(spendingView.getOrphanedAllocations().get(0).getAllocatedAmount()).isEqualByComparingTo("10000.00");
        assertThat(spendingView.getOrphanedAllocations().get(0).isMilestoneDeleted()).isTrue();

        // The FUNDING event spanned both: Sub A's allocation is still live, Sub B's is the orphan.
        assertThat(fundingView.getProjectAllocations()).hasSize(2);
        assertThat(fundingView.getProjectAllocations().get(0).isContainsDeletedMilestones()).isFalse();
        assertThat(fundingView.getProjectAllocations().get(1).isContainsDeletedMilestones()).isTrue();
        assertThat(fundingView.getOrphanedAllocations()).hasSize(1);
        assertThat(fundingView.getOrphanedAllocations().get(0).getAllocatedAmount()).isEqualByComparingTo("15000.00");

        // Step 6 (before fixing anything): the orphan cleanup deletes only the SPENDING event — its sole
        // allocation points at the deleted milestone. The FUNDING event still has a live Sub A
        // allocation, so it is left for a human to fix, even though it is in ERROR too.
        lenient().when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));
        OrphanEventsCleanupView cleanup = spendingEventService.deleteOrphanedErrorEvents(ORG_ID);

        assertThat(cleanup.getError()).isEmpty();
        assertThat(cleanup.getDeletedEvents()).hasSize(1);
        assertThat(cleanup.getDeletedEvents().get(0).getEventId().trim()).isEqualTo(spending.getEventId());
        assertThat(fundingEventRepository.findById(spending.getEventId())).isEmpty();
        assertThat(allocationRepository.findById_EventId(spending.getEventId())).isEmpty();
        assertThat(fundingEventRepository.findById(funding.getEventId())).isPresent();
        assertThat(allocationRepository.findById_EventId(funding.getEventId())).hasSize(2);

        // Step 5: fixing the FUNDING event by hand (allocations only reference what still exists) replaces
        // all its allocations — the dangling one is dropped and the event is DRAFT again.
        SpendingEventView fixed = spendingEventService.updateEvent(funding.getEventId(), SpendingEventCreateRequest.builder()
                .organisationId(ORG_ID).eventType(EventType.FUNDING).fundingId("GRANT-ATLAS-E2E-1")
                .fundingHash("atlas-e2e-hash-1").fundingEntity("Cardano Foundation").currencyRcy("ADA")
                .eventDate(LocalDate.of(2026, 9, 15)).amountRcy(new BigDecimal("20000.00"))
                .allocations(List.of(EventProjectAllocationRequest.builder().projectTitle("Project Atlas")
                        .subProjects(List.of(EventSubProjectAllocationRequest.builder().projectTitle("Sub A")
                                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                        .milestone(MilestoneCreateRequest.builder().milestoneTitle("Milestone A1").build())
                                        .allocatedAmount(new BigDecimal("20000.00")).build()))
                                .build()))
                        .build()))
                .build());

        assertThat(fixed.getError()).isEmpty();
        assertThat(fixed.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(fixed.getOrphanedAllocations()).isEmpty();
        assertThat(allocationRepository.findById_EventId(funding.getEventId())).hasSize(1);
    }

    /** Reduced repro: same "update Sub A + delete Sub B in one PUT" shape, but with no events at all — isolates whether events are required to trigger the TransientObjectException. */
    @Test
    void updatingSubA_andDeletingSubB_withNoEventsInvolved_doesNotThrow() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
        Currency activeCurrency = new Currency(new Currency.Id(ORG_ID, "x"), "ISO_4217:x", true);
        lenient().when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(anyString(), anyString()))
                .thenReturn(Optional.of(activeCurrency));

        ProjectView created = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-ATLAS2").projectTitle("Project Atlas 2")
                .proId("PRJ-2001-E2E").totalAmount(new BigDecimal("80000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS2-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone A1").milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 15)).build()))
                                .build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS2-B").projectTitle("Sub B")
                                .totalAmount(new BigDecimal("30000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone B1").milestoneAmount(new BigDecimal("30000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 20)).build()))
                                .build()))
                .build());
        assertThat(created.getError()).isEmpty();
        String rootId = created.getProjectId();

        ProjectView updated = projectTreeUpdateService.updateWithMilestones(rootId, ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-ATLAS2").projectTitle("Project Atlas 2")
                .proId("PRJ-2001-E2E").totalAmount(new BigDecimal("50000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS2-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone A1").milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 15)).build()))
                                .build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-ATLAS2-B").projectTitle("Sub B")
                                .action("DELETE").build()))
                .build());

        assertThat(updated.getError()).isEmpty();
        assertThat(updated.getAffectedEvents()).isEmpty();
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "org.cardanofoundation.lob.app.funding",
            "org.cardanofoundation.lob.app.support.security",
            "org.cardanofoundation.lob.app.organisation.service.csv"
    }, excludeFilters = {
            @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EventPublishJob.class),
            @Filter(type = FilterType.REGEX, pattern = "org\\.cardanofoundation\\.lob\\.app\\.funding\\.e2e\\..*")
    })
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.lob.app.funding.repository")
    @EntityScan(basePackages = {
            "org.cardanofoundation.lob.app.funding.domain.entity",
            "org.cardanofoundation.lob.app.support.spring_audit.internal"
    })
    static class TestConfig {
    }

    /**
     * A PUT that deletes a sub-project (cascading to its events) but then fails the whole-tree budget
     * check must roll back everything, delete included: the sub-project, its allocation rows and its
     * event's DRAFT status are all exactly as before the request.
     */
    @Test
    void failingPut_rollsBackTheCascadeDeleteAndEveryEventFlag() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
        Currency activeCurrency = new Currency(new Currency.Id(ORG_ID, "x"), "ISO_4217:x", true);
        lenient().when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(anyString(), anyString()))
                .thenReturn(Optional.of(activeCurrency));

        ProjectView created = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-RB").projectTitle("Project Rollback")
                .proId("PRJ-RB-E2E").totalAmount(new BigDecimal("80000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-RB-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone A1").milestoneAmount(new BigDecimal("50000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 15)).build()))
                                .build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-RB-B").projectTitle("Sub B")
                                .totalAmount(new BigDecimal("30000.00"))
                                .milestones(List.of(MilestoneCreateRequest.builder()
                                        .milestoneTitle("Milestone B1").milestoneAmount(new BigDecimal("30000.00"))
                                        .currency("ADA").milestoneDate(LocalDate.of(2027, 10, 20)).build()))
                                .build()))
                .build());
        assertThat(created.getError()).isEmpty();
        String rootId = created.getProjectId();

        SpendingEventView funding = spendingEventService.createEvent(SpendingEventCreateRequest.builder()
                .organisationId(ORG_ID).eventType(EventType.FUNDING).fundingId("GRANT-RB-E2E-1")
                .fundingHash("rb-e2e-hash-1").fundingEntity("Cardano Foundation").currencyRcy("ADA")
                .eventDate(LocalDate.of(2026, 9, 15)).amountRcy(new BigDecimal("15000.00"))
                .allocations(List.of(EventProjectAllocationRequest.builder().projectTitle("Project Rollback")
                        .subProjects(List.of(EventSubProjectAllocationRequest.builder().projectTitle("Sub B")
                                .milestones(List.of(EventMilestoneAllocationRequest.builder()
                                        .milestone(MilestoneCreateRequest.builder().milestoneTitle("Milestone B1").build())
                                        .allocatedAmount(new BigDecimal("15000.00")).build()))
                                .build()))
                        .build()))
                .build());
        assertThat(funding.getError()).isEmpty();

        // Deletes Sub B, but also sets the root total (40,000) below Sub A's own 50,000 — rejected at the end.
        ProjectView rejected = projectTreeUpdateService.updateWithMilestones(rootId, ProjectWithMilestonesCreateRequest.builder()
                .organisationId(ORG_ID).externalProjectId("PROJ-RB").projectTitle("Project Rollback")
                .proId("PRJ-RB-E2E").totalAmount(new BigDecimal("40000.00")).currency("ADA")
                .subProjects(List.of(
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-RB-A").projectTitle("Sub A")
                                .totalAmount(new BigDecimal("50000.00")).build(),
                        ProjectTreeNodeRequest.builder().externalProjectId("PROJ-RB-B").projectTitle("Sub B")
                                .action("DELETE").build()))
                .build());

        assertThat(rejected.getError().orElseThrow().getTitle()).isEqualTo(ErrorTitleConstants.PROJECT_AMOUNT_BELOW_SUBPROJECTS);
        assertThat(projectRepository.findByParentProjectIdAndProjectTitle(rootId, "Sub B")).isPresent();
        assertThat(projectRepository.findById(rootId).orElseThrow().getTotalAmount()).isEqualByComparingTo("80000.00");
        assertThat(fundingEventRepository.findById(funding.getEventId()).orElseThrow().getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(allocationRepository.findById_EventId(funding.getEventId())).hasSize(1);
        assertThat(spendingEventService.getEvent(funding.getEventId()).getOrphanedAllocations()).isEmpty();
    }

}
