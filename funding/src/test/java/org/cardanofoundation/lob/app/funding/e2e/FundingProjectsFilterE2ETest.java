package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.cardanofoundation.lob.app.funding.domain.enums.ProjectAmountTarget;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectFilterRequest;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingEventRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.service.ProjectService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Runs the organisation projects-list filters against a real Postgres (Testcontainers): the recursive
 * structure query (deep search, rolled-up Funded/Spent), the null-means-off parameters and the inclusive
 * range edges are SQL behaviour a mocked repository cannot prove.
 *
 * <p>Fixture (organisation {@value #ORG}):
 * <ul>
 *   <li>p-edu "Education Grant", USD, budget 1000 — milestones "Teacher training" and "Teacher kits";
 *       FUNDING 400, REFUND 100, SPENDING 50 → funded 300, spent 50</li>
 *   <li>p-health "Health Programme", EUR, budget 5000 — sub-project "Clinic build" → sub-sub-project
 *       "Rural Outreach" → milestone "Vaccination drive"; FUNDING 2000, SPENDING 1500 → funded 2000, spent 1500</li>
 *   <li>p-infra "Infrastructure", USD, budget 300 — milestone "Bridge", no events → funded 0, spent 0</li>
 *   <li>p-other: another organisation, never returned</li>
 * </ul>
 */
@SpringBootTest(classes = FundingProjectsFilterE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingProjectsFilterE2ETest {

    private static final String ORG = "org-pfilter";
    private static final Pageable ALL = PageRequest.of(0, 50, Sort.by("projectTitle"));

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
    private ProjectService projectService;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @BeforeEach
    void seed() {
        when(keycloakSecurityHelper.canUserAccessOrg(any())).thenReturn(true);
        when(organisationPublicApi.findByOrganisationId(any())).thenReturn(Optional.of(mock(Organisation.class)));
        if (projectRepository.existsById("p-edu")) {
            return;
        }
        ProjectEntity education = root("p-edu", ORG, "Education Grant", "USD", "1000");
        MilestoneEntity training = milestone("ms-training", education, "Teacher training");
        milestone("ms-kits", education, "Teacher kits");
        event("evt-edu-fund", EventType.FUNDING, "400", training);
        event("evt-edu-refund", EventType.REFUND, "100", training);
        event("evt-edu-spend", EventType.SPENDING, "50", training);

        ProjectEntity health = root("p-health", ORG, "Health Programme", "EUR", "5000");
        ProjectEntity clinic = subProject("p-clinic", health, "Clinic build");
        ProjectEntity outreach = subProject("p-outreach", clinic, "Rural Outreach");
        MilestoneEntity vaccination = milestone("ms-vaccination", outreach, "Vaccination drive");
        event("evt-health-fund", EventType.FUNDING, "2000", vaccination);
        event("evt-health-spend", EventType.SPENDING, "1500", vaccination);

        ProjectEntity infra = root("p-infra", ORG, "Infrastructure", "USD", "300");
        milestone("ms-bridge", infra, "Bridge");

        root("p-other", "other-org", "Education Elsewhere", "USD", "1000");
    }

    @Test
    void noFilters_keepsTheFlatListOfEveryProjectOfTheOrganisation() {
        PagedResponse<ProjectView> result = list(filter().build(), ALL);

        assertThat(ids(result)).containsExactly("p-clinic", "p-edu", "p-health", "p-infra", "p-outreach");
        assertThat(result.getTotal()).isEqualTo(5);
    }

    @Test
    void search_matchesTheRootTitle_caseInsensitivePartial() {
        assertThat(rootIds(filter().search("EDUCAT").build())).containsExactly("p-edu");
    }

    @Test
    void search_matchesSubProjectAndMilestoneTitlesAtAnyDepth_andReturnsTheRootWithItsSubProjects() {
        PagedResponse<ProjectView> result = list(filter().search("outreach").build(), ALL);

        assertThat(ids(result)).containsExactly("p-health", "p-clinic", "p-outreach");
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(rootIds(filter().search("vaccin").build())).containsExactly("p-health");
        assertThat(rootIds(filter().search("training").build())).containsExactly("p-edu");
    }

    @Test
    void search_rootMatchingThroughSeveralChildren_isReturnedAndCountedOnce() {
        PagedResponse<ProjectView> result = list(filter().search("teacher").build(), ALL);

        assertThat(ids(result)).containsExactly("p-edu");
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void search_withNoMatch_returnsNothing() {
        PagedResponse<ProjectView> result = list(filter().search("nope").build(), ALL);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    @Test
    void currency_matchesAnyOfTheGivenRootCurrencies() {
        assertThat(rootIds(filter().currencies(List.of("eur")).build())).containsExactly("p-health");
        assertThat(rootIds(filter().currencies(List.of("USD", "EUR")).build())).containsExactly("p-edu", "p-health", "p-infra");
        assertThat(rootIds(filter().currencies(List.of("GBP")).build())).isEmpty();
    }

    @Test
    void budgetedRange_isInclusive_andIsTheDefaultTarget() {
        assertThat(rootIds(filter().minAmount(new BigDecimal("1000")).build())).containsExactly("p-edu", "p-health");
        assertThat(rootIds(filter().maxAmount(new BigDecimal("1000")).build())).containsExactly("p-edu", "p-infra");
        assertThat(rootIds(filter().amountTarget(ProjectAmountTarget.BUDGETED)
                .minAmount(new BigDecimal("1000.00")).maxAmount(new BigDecimal("1000")).build())).containsExactly("p-edu");
    }

    @Test
    void fundedRange_isNetOfRefunds_rolledUpThroughSubProjects_andInclusive() {
        assertThat(rootIds(funded("300", null))).containsExactly("p-edu", "p-health");
        assertThat(rootIds(funded(null, "300"))).containsExactly("p-edu", "p-infra");
        assertThat(rootIds(funded("300", "300"))).containsExactly("p-edu");
        assertThat(rootIds(funded("2000", "2000"))).containsExactly("p-health");
    }

    @Test
    void spentRange_rolledUpThroughSubProjects_andInclusive() {
        assertThat(rootIds(spent("50", null))).containsExactly("p-edu", "p-health");
        assertThat(rootIds(spent(null, "50"))).containsExactly("p-edu", "p-infra");
        assertThat(rootIds(spent("1500", "1500"))).containsExactly("p-health");
        assertThat(rootIds(spent("0", "0"))).containsExactly("p-infra");
    }

    @Test
    void filters_combineWithAnd() {
        assertThat(rootIds(filter().currencies(List.of("USD")).amountTarget(ProjectAmountTarget.FUNDED)
                .minAmount(new BigDecimal("1")).build())).containsExactly("p-edu");
        assertThat(rootIds(filter().search("outreach").currencies(List.of("USD")).build())).isEmpty();
    }

    @Test
    void pagination_countsTheMatchingRootProjects() {
        ProjectFilterRequest filter = filter().currencies(List.of("USD")).build();

        PagedResponse<ProjectView> first = list(filter, PageRequest.of(0, 1, Sort.by("projectTitle")));
        PagedResponse<ProjectView> second = list(filter, PageRequest.of(1, 1, Sort.by("projectTitle")));

        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(ids(first)).containsExactly("p-edu");
        assertThat(ids(second)).containsExactly("p-infra");
    }

    private static ProjectFilterRequest.ProjectFilterRequestBuilder filter() {
        return ProjectFilterRequest.builder();
    }

    private static ProjectFilterRequest funded(String min, String max) {
        return amountFilter(ProjectAmountTarget.FUNDED, min, max);
    }

    private static ProjectFilterRequest spent(String min, String max) {
        return amountFilter(ProjectAmountTarget.SPENT, min, max);
    }

    private static ProjectFilterRequest amountFilter(ProjectAmountTarget target, String min, String max) {
        return filter().amountTarget(target)
                .minAmount(min == null ? null : new BigDecimal(min))
                .maxAmount(max == null ? null : new BigDecimal(max))
                .build();
    }

    private PagedResponse<ProjectView> list(ProjectFilterRequest filter, Pageable pageable) {
        PagedResponse<ProjectView> result = projectService.listProjects(ORG, filter, pageable);
        assertThat(result.getError()).isEmpty();
        return result;
    }

    private List<String> rootIds(ProjectFilterRequest filter) {
        return list(filter, ALL).getContent().stream()
                .filter(p -> p.getParentProjectId() == null)
                .map(p -> p.getProjectId().trim())
                .toList();
    }

    // Ids are CHAR columns: Postgres pads the stored value, hence the trim.
    private static List<String> ids(PagedResponse<ProjectView> result) {
        return result.getContent().stream().map(p -> p.getProjectId().trim()).toList();
    }

    private ProjectEntity root(String id, String organisationId, String title, String currency, String totalAmount) {
        return projectRepository.saveAndFlush(ProjectEntity.builder()
                .id(id).organisationId(organisationId).externalProjectId("EXT-" + id).proId("PRJ-" + id)
                .projectTitle(title).totalAmount(new BigDecimal(totalAmount)).currency(currency).build());
    }

    private ProjectEntity subProject(String id, ProjectEntity parent, String title) {
        return projectRepository.saveAndFlush(ProjectEntity.builder()
                .id(id).organisationId(ORG).externalProjectId("EXT-" + id).proId("PRJ-" + id)
                .projectTitle(title).parentProject(parent).build());
    }

    private MilestoneEntity milestone(String id, ProjectEntity project, String title) {
        return milestoneRepository.saveAndFlush(MilestoneEntity.builder()
                .id(id).project(project).proId("PRJ-" + id).milestoneTitle(title)
                .milestoneAmount(new BigDecimal("50000.00")).currency("USD").milestoneDate(LocalDate.of(2027, 1, 1)).build());
    }

    private void event(String id, EventType type, String amount, MilestoneEntity milestone) {
        fundingEventRepository.saveAndFlush(FundingEventEntity.builder()
                .id(id).eventType(type).status(EventStatus.DRAFT).organisationId(ORG).fundingId("GRANT-" + id)
                .currencyRcy("USD").eventDate(LocalDate.of(2026, 1, 1)).totalAmount(new BigDecimal(amount)).build());
        allocationRepository.saveAndFlush(EventMilestoneAllocationEntity.builder()
                .id(new EventMilestoneAllocationEntity.Id(id, milestone.getId()))
                .allocatedAmount(new BigDecimal(amount)).build());
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
