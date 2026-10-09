package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.vavr.control.Either;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.service.FundingCascadeDeleteService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Real end-to-end proof, against a real Postgres (Testcontainers), of the LOB-2365 follow-up design:
 * deleting a milestone must not silently take its {@code funding_event_milestone_allocation} row with
 * it. This can only be verified against a real database — {@code funding_event_milestone_allocation
 * .milestone_id} used to be FK-enforced (ON DELETE CASCADE), which is exactly what forced the earlier
 * design to detach the allocation before a milestone could be deleted at all; migration
 * {@code V1.8_200_9} drops that constraint. A mocked unit test (see
 * {@code FundingCascadeDeleteServiceTest}) cannot observe this — it has no real constraint to violate.
 */
@SpringBootTest(classes = FundingCascadeDeleteE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingCascadeDeleteE2ETest {

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
    private FundingProjectRepository projectRepository;
    @Autowired
    private MilestoneRepository milestoneRepository;
    @Autowired
    private FundingEventRepository fundingEventRepository;
    @Autowired
    private EventMilestoneAllocationRepository allocationRepository;
    @Autowired
    private FundingCascadeDeleteService cascadeDeleteService;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @Test
    void deletingAMilestone_leavesItsAllocationRowInPlace_danglingAndFlaggedForReview() {
        ProjectEntity project = projectRepository.saveAndFlush(ProjectEntity.builder()
                .id("e2e-proj-1").organisationId("org-e2e").externalProjectId("EXT-E2E-1").proId("PRJ-E2E-1")
                .projectTitle("E2E Project").totalAmount(new BigDecimal("100000.00")).currency("USD").build());

        MilestoneEntity milestone = milestoneRepository.saveAndFlush(MilestoneEntity.builder()
                .id("e2e-ms-1").project(project).proId("PRJ-E2E-1-1").milestoneTitle("E2E Milestone")
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(LocalDate.of(2027, 1, 1)).build());

        FundingEventEntity event = fundingEventRepository.saveAndFlush(FundingEventEntity.builder()
                .id("e2e-evt-1").eventType(EventType.FUNDING).status(EventStatus.DRAFT)
                .organisationId("org-e2e").fundingId("GRANT-E2E-1").currencyRcy("USD")
                .totalAmount(new BigDecimal("20000.00")).build());

        allocationRepository.saveAndFlush(EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id(event.getId(), milestone.getId()))
                .allocatedAmount(new BigDecimal("20000.00")).build());

        // Re-fetch rather than reuse the just-built instance: Persistable#isNew() only flips to false
        // via @PostLoad, which persist() (what saveAndFlush used above) never triggers — passing the
        // still-"new" in-memory object straight to delete() would make Spring Data's delete() silently
        // no-op, exactly like the real MilestoneService always finds it by id before deleting it.
        MilestoneEntity managedMilestone = milestoneRepository.findById(milestone.getId()).orElseThrow();

        Either<ProblemDetail, List<FundingEventEntity>> result = cascadeDeleteService.deleteMilestone(managedMilestone);

        assertThat(result.isRight()).isTrue();
        // event_id is CHAR(64) — Postgres pads the stored value with trailing spaces (insignificant to
        // SQL comparisons, but present in whatever JDBC hands back to Java), hence the trim here.
        assertThat(result.get()).extracting(e -> e.getId().trim()).containsExactly(event.getId());

        // The milestone row is genuinely gone — not just hidden from the app.
        assertThat(milestoneRepository.findById(milestone.getId())).isEmpty();

        // The allocation row survives untouched — this is the part that used to be impossible: the old
        // ON DELETE CASCADE FK would have taken this row out along with the milestone. Deliberately not
        // calling survivors.get(0).getMilestone() here — see that getter's Javadoc: it throws
        // EntityNotFoundException for a dangling id (we don't use @NotFound(IGNORE) to suppress that,
        // since doing so forces the association EAGER and breaks flush elsewhere — see
        // EventMilestoneAllocationEntity's Javadoc and ProjectTreeUpdateE2ETest). The milestoneRepository
        // check above is the correct way to confirm a milestone is gone.
        List<EventMilestoneAllocationEntity> survivors = allocationRepository.findById_EventId(event.getId());
        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).getAllocatedAmount()).isEqualByComparingTo("20000.00");

        // The event is flagged for a human to review; its own data (including the allocation above) is
        // never rewritten to get there.
        assertThat(fundingEventRepository.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(EventStatus.ERROR);
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "org.cardanofoundation.lob.app.funding",
            "org.cardanofoundation.lob.app.support.security",
            "org.cardanofoundation.lob.app.organisation.service.csv"
    }, excludeFilters = {
            @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EventPublishJob.class),
            // org.cardanofoundation.lob.app.funding.e2e (this package) is itself under the
            // org.cardanofoundation.lob.app.funding scan root above, so without this exclusion this
            // scan also picks up every OTHER e2e test's own nested @Configuration TestConfig (e.g.
            // FundingBulkImportE2ETest.TestConfig) and double-registers its repository beans in this
            // context — mirror this exclusion in any new e2e test class added under this package.
            @Filter(type = FilterType.REGEX, pattern = "org\\.cardanofoundation\\.lob\\.app\\.funding\\.e2e\\..*")
    })
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.lob.app.funding.repository")
    @EntityScan(basePackages = {
            "org.cardanofoundation.lob.app.funding.domain.entity",
            "org.cardanofoundation.lob.app.support.spring_audit.internal"
    })
    static class TestConfig {
    }
}
