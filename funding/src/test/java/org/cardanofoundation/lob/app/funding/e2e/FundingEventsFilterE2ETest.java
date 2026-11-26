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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventFilterRequest;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.service.SpendingEventService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Runs the organisation events-list filter query against a real Postgres (Testcontainers): the
 * null-means-off parameters, the inclusive range edges and the project-title {@code EXISTS} over
 * milestone allocations are all SQL behaviour a mocked repository cannot prove.
 *
 * <p>Fixture: "Education Grant" (root, milestone ms-edu), "Health Programme" (root) with sub-project
 * "Education Outreach" (milestone ms-out), "Infrastructure" (root, milestone ms-infra).
 * <ul>
 *   <li>evt-1: FUNDING, DRAFT, USD, 2026-01-01, 100 — allocated to ms-edu and ms-out (two matching projects)</li>
 *   <li>evt-2: SPENDING, PUBLISHED, USD / EUR fcy, 2026-01-15, 500 — allocated to ms-infra</li>
 *   <li>evt-3: REFUND, ERROR, USD, 2026-01-31, 1000 — allocated to ms-out</li>
 *   <li>evt-4: another organisation, never returned</li>
 * </ul>
 */
@SpringBootTest(classes = FundingEventsFilterE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingEventsFilterE2ETest {

    private static final String ORG = "org-filter";
    private static final Pageable ALL = PageRequest.of(0, 50, Sort.by("eventDate"));

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
    private SpendingEventService spendingEventService;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @BeforeEach
    void seed() {
        if (fundingEventRepository.existsById("evt-1")) {
            return;
        }
        ProjectEntity education = project("p-edu", "Education Grant", null);
        ProjectEntity health = project("p-health", "Health Programme", null);
        ProjectEntity outreach = project("p-out", "Education Outreach", health);
        ProjectEntity infra = project("p-infra", "Infrastructure", null);

        MilestoneEntity msEdu = milestone("ms-edu", education);
        MilestoneEntity msOut = milestone("ms-out", outreach);
        MilestoneEntity msInfra = milestone("ms-infra", infra);

        event("evt-1", ORG, EventType.FUNDING, EventStatus.DRAFT, null, LocalDate.of(2026, 1, 1), "100", msEdu, msOut);
        event("evt-2", ORG, EventType.SPENDING, EventStatus.PUBLISHED, "EUR", LocalDate.of(2026, 1, 15), "500", msInfra);
        event("evt-3", ORG, EventType.REFUND, EventStatus.ERROR, null, LocalDate.of(2026, 1, 31), "1000", msOut);
        event("evt-4", "other-org", EventType.FUNDING, EventStatus.DRAFT, null, LocalDate.of(2026, 1, 15), "500", msEdu);
    }

    @Test
    void noFilters_returnsAllEventsOfTheOrganisation() {
        assertThat(ids(filter().build())).containsExactly("evt-1", "evt-2", "evt-3");
    }

    @Test
    void projectTitle_isCaseInsensitivePartial_andReturnsAnEventOnceEvenWhenSeveralProjectsMatch() {
        Page<FundingEventEntity> page = find(filter().projectTitle("EDUCAT").build(), ALL);

        // evt-1 is allocated to two "Educat…" projects but appears once, and the count agrees.
        assertThat(ids(page)).containsExactly("evt-1", "evt-3");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void projectTitle_matchesTheParentOfASubProject() {
        assertThat(ids(filter().projectTitle("health").build())).containsExactly("evt-1", "evt-3");
    }

    @Test
    void projectTitle_withNoMatch_returnsNothing() {
        assertThat(ids(filter().projectTitle("nope").build())).isEmpty();
    }

    @Test
    void eventTypes_matchAnyOfTheGivenTypes() {
        assertThat(ids(filter().eventTypes(List.of(EventType.FUNDING, EventType.REFUND)).build()))
                .containsExactly("evt-1", "evt-3");
        assertThat(ids(filter().eventTypes(List.of(EventType.SPENDING)).build())).containsExactly("evt-2");
    }

    @Test
    void statuses_matchAnyOfTheGivenStatuses() {
        assertThat(ids(filter().statuses(List.of(EventStatus.DRAFT, EventStatus.ERROR)).build()))
                .containsExactly("evt-1", "evt-3");
        assertThat(ids(filter().statuses(List.of(EventStatus.PUBLISHED)).build())).containsExactly("evt-2");
    }

    @Test
    void currency_matchesReportingOrForeignCurrency() {
        assertThat(ids(filter().currency("usd").build())).containsExactly("evt-1", "evt-2", "evt-3");
        assertThat(ids(filter().currency("EUR").build())).containsExactly("evt-2");
        assertThat(ids(filter().currency("GBP").build())).isEmpty();
    }

    @Test
    void dateRange_isInclusiveAtBothEnds_andEachEndWorksAlone() {
        assertThat(ids(filter().dateFrom(LocalDate.of(2026, 1, 15)).build())).containsExactly("evt-2", "evt-3");
        assertThat(ids(filter().dateTo(LocalDate.of(2026, 1, 15)).build())).containsExactly("evt-1", "evt-2");
        assertThat(ids(filter().dateFrom(LocalDate.of(2026, 1, 1)).dateTo(LocalDate.of(2026, 1, 31)).build()))
                .containsExactly("evt-1", "evt-2", "evt-3");
        assertThat(ids(filter().dateFrom(LocalDate.of(2026, 1, 15)).dateTo(LocalDate.of(2026, 1, 15)).build()))
                .containsExactly("evt-2");
    }

    @Test
    void amountRange_isInclusiveAtBothEnds_andEachEndWorksAlone() {
        assertThat(ids(filter().minAmount(new BigDecimal("500")).build())).containsExactly("evt-2", "evt-3");
        assertThat(ids(filter().maxAmount(new BigDecimal("500")).build())).containsExactly("evt-1", "evt-2");
        assertThat(ids(filter().minAmount(new BigDecimal("100")).maxAmount(new BigDecimal("1000")).build()))
                .containsExactly("evt-1", "evt-2", "evt-3");
        assertThat(ids(filter().minAmount(new BigDecimal("500.00")).maxAmount(new BigDecimal("500")).build()))
                .containsExactly("evt-2");
    }

    @Test
    void filters_combineWithAnd() {
        assertThat(ids(filter().projectTitle("education").statuses(List.of(EventStatus.ERROR))
                .minAmount(new BigDecimal("1000")).build())).containsExactly("evt-3");
        assertThat(ids(filter().eventTypes(List.of(EventType.SPENDING)).currency("EUR")
                .dateFrom(LocalDate.of(2026, 1, 1)).dateTo(LocalDate.of(2026, 1, 14)).build())).isEmpty();
    }

    @Test
    void pagination_countsTheFilteredResultSet() {
        SpendingEventFilterRequest filter = filter().projectTitle("educat").build();

        Page<FundingEventEntity> first = find(filter, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "eventDate")));
        Page<FundingEventEntity> second = find(filter, PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "eventDate")));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(ids(first)).containsExactly("evt-3");
        assertThat(ids(second)).containsExactly("evt-1");
    }

    private static SpendingEventFilterRequest.SpendingEventFilterRequestBuilder filter() {
        return SpendingEventFilterRequest.builder();
    }

    private Page<FundingEventEntity> find(SpendingEventFilterRequest filter, Pageable pageable) {
        return spendingEventService.findByOrganisationIdAndFilter(ORG, filter, pageable);
    }

    private List<String> ids(SpendingEventFilterRequest filter) {
        return ids(find(filter, ALL));
    }

    // event_id is CHAR(64): Postgres pads the stored value, hence the trim.
    private static List<String> ids(Page<FundingEventEntity> page) {
        return page.getContent().stream().map(e -> e.getId().trim()).toList();
    }

    private ProjectEntity project(String id, String title, ProjectEntity parent) {
        return projectRepository.saveAndFlush(ProjectEntity.builder()
                .id(id).organisationId(ORG).externalProjectId("EXT-" + id).proId("PRJ-" + id)
                .projectTitle(title).parentProject(parent)
                .totalAmount(new BigDecimal("100000.00")).currency("USD").build());
    }

    private MilestoneEntity milestone(String id, ProjectEntity project) {
        return milestoneRepository.saveAndFlush(MilestoneEntity.builder()
                .id(id).project(project).proId("PRJ-" + id).milestoneTitle("Milestone " + id)
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(LocalDate.of(2027, 1, 1)).build());
    }

    private void event(String id, String organisationId, EventType type, EventStatus status, String currencyFcy,
                       LocalDate eventDate, String totalAmount, MilestoneEntity... milestones) {
        fundingEventRepository.saveAndFlush(FundingEventEntity.builder()
                .id(id).eventType(type).status(status).organisationId(organisationId).fundingId("GRANT-" + id)
                .currencyRcy("USD").currencyFcy(currencyFcy).eventDate(eventDate)
                .totalAmount(new BigDecimal(totalAmount)).build());
        for (MilestoneEntity milestone : milestones) {
            allocationRepository.saveAndFlush(EventMilestoneAllocationEntity.builder()
                    .id(new EventMilestoneAllocationEntity.Id(id, milestone.getId()))
                    .allocatedAmount(new BigDecimal(totalAmount)).build());
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "org.cardanofoundation.lob.app.funding",
            "org.cardanofoundation.lob.app.support.security",
            "org.cardanofoundation.lob.app.organisation.service.csv"
    }, excludeFilters = {
            @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EventPublishJob.class),
            // See FundingCascadeDeleteE2ETest: keep other e2e tests' nested TestConfigs out of this context.
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
