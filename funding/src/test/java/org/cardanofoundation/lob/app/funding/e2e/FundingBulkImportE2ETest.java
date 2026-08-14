package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.multipart.MultipartFile;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowError;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Real end-to-end regression tests for the bulk CSV importer, run against a real Postgres
 * (Testcontainers) through the actual, Spring-proxied {@link FundingBulkImportService} bean chain —
 * the one place in the suite that can observe real DB effects (rollback, persisted rows) rather than
 * mocked ones. {@code FundingBulkImportServiceTest} runs outside a Spring container, where its
 * transaction runners are inert no-ops, so it cannot prove DB-level behavior.
 *
 * <p>All CSV fixtures are embedded as string constants below rather than read from disk — earlier
 * versions of this test read them from the author's local {@code ~/Downloads}, which doesn't exist on
 * CI and made every one of these tests fail there.
 *
 * <p>Covers: the orphan-root-project rollback fix (missing-sub-project-title), the Events file's
 * event-type-conditional column validation, the duplicate-milestone-allocation crash fix, and that
 * External Project ID / External Milestone ID stay strict, exact-match references (a project's title
 * is not an acceptable substitute, even when the title is real and seeded).
 */
@SpringBootTest(classes = FundingBulkImportE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingBulkImportE2ETest {

    private static final String ORG_ID = "org1";

    private static final String PROJECTS_TEMPLATE_CSV = """
            External Project ID,Project Title,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Total Amount,Sub Currency
            PROJ-A,Project A,GRANT-2025-001,100000.00,USD,,SUB-1,Sub One,,40000.00,USD
            """;

    private static final String MILESTONES_TEMPLATE_CSV = """
            External Project ID,External Milestone ID,Milestone Title,Milestone Amount,Currency,Milestone Date
            SUB-1,MS-1,Milestone One,20000.00,USD,2026-06-30
            SUB-1,MS-2,Milestone Two,20000.00,USD,2026-07-15
            """;

    private static final String EVENTS_TEMPLATE_CSV = """
            Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount
            FUNDING,GRANT-2025-001,,Cardano Foundation,USD,2026-07-01,,,,,,,,,SUB-1,MS-1,20000.00
            FUNDING,GRANT-2025-001,,Cardano Foundation,USD,2026-07-01,,,,,,,,,SUB-1,MS-2,20000.00
            SPENDING,GRANT-2025-001,,,USD,2026-07-20,Personnel,Vendor AB,36000.00,EUR,0.9,40000.00,,Invoice #INV-001,SUB-1,MS-1,20000.00
            SPENDING,GRANT-2025-001,,,USD,2026-07-20,Personnel,Vendor AB,36000.00,EUR,0.9,40000.00,,Invoice #INV-001,SUB-1,MS-2,20000.00
            """;

    private static final String EVENTS_DUPLICATE_MILESTONE_ALLOCATION_CSV = """
            Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount
            FUNDING,GRANT-2025-Z,,Cardano Foundation,USD,2026-07-01,,,,,,,,39bb7762-5cfb-430b-bfe7-b0982fc27316,d3978bf7-e9b0-493c-b821-debdaee55708,2000.00
            FUNDING,GRANT-2025-Z,,Cardano Foundation,USD,2026-07-01,,,,,,,,39bb7762-5cfb-430b-bfe7-b0982fc27316,d3978bf7-e9b0-493c-b821-debdaee55708,2000.00
            """;

    // Funding ID missing -> optional, row succeeds.
    private static final String MISSING_FUNDING_ID_CSV = """
            External Project ID,Project Title,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Total Amount,Sub Currency
            PROJ-A,Project A,100000.00,USD,,SUB-1,Sub One,,40000.00,USD
            """;

    // Root Project Title missing -> required; fails before any persistence (pre-existing behavior,
    // not part of the orphan-root fix).
    private static final String MISSING_PROJECT_TITLE_CSV = """
            External Project ID,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Total Amount,Sub Currency
            PROJ-A,GRANT-2025-001,100000.00,USD,,SUB-1,Sub One,,40000.00,USD
            """;

    // Sub Currency missing -> now required to match the REST API's stricter shape; fails, root
    // rolled back as an orphan (no surviving sub-project).
    private static final String MISSING_SUB_CURRENCY_CSV = """
            External Project ID,Project Title,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Total Amount
            PROJ-D,Project D,GRANT-2025-003,100000.00,USD,,SUB-3,Sub One,,40000.00
            """;

    // Sub Project Title missing -> required; this is the orphan-root case the fix covers.
    private static final String MISSING_SUB_PROJECT_TITLE_CSV = """
            External Project ID,Project Title,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Funding ID,Sub Total Amount,Sub Currency
            PROJ-E,Project E,GRANT-2025-004,100000.00,USD,,SUB-1,,40000.00,USD
            """;

    // Sub Total Amount missing -> now required to match the REST API's stricter shape; fails, root
    // rolled back as an orphan.
    private static final String MISSING_SUB_TOTAL_AMOUNT_CSV = """
            External Project ID,Project Title,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Currency
            PROJ-C,Project C,GRANT-2025-002,100000.00,USD,,SUB-1,Sub One,,USD
            """;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.3");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // funding's own migrations, plus support's (revinfo) and organisation's, all land under this
        // same classpath location since funding depends on both modules.
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgresql/common");
        // "validate" fails: several id columns are CHAR(64) in the migrations but mapped as plain
        // String @Id (no columnDefinition), which Hibernate's strict validator flags as a VARCHAR
        // mismatch even though it's a harmless, working mapping at the JDBC level. Flyway is the
        // schema source of truth here, so skip Hibernate's own DDL validation/generation entirely.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // The real CSV files used here are comma-separated; the app-wide default is ';'.
        registry.add("lob.csv.delimiter", () -> ",");
    }

    @Autowired
    private FundingBulkImportService bulkImportService;
    @Autowired
    private FundingProjectRepository projectRepository;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @BeforeEach
    void allowOrganisationAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
    }

    @Test
    void eventsTemplateCsv_seededWithMatchingProjectsAndMilestones_importsCleanly() {
        when(organisationPublicApi.findByOrganisationId("org-events-good")).thenReturn(Optional.of(new Organisation()));
        seedProjectsAndMilestonesTemplate("org-events-good");

        MultipartFile file = new MockMultipartFile("file", "funding_events_template.csv", "text/csv", EVENTS_TEMPLATE_CSV.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-good")
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(2);
    }

    @Test
    void fundingEventSingleRow_noAmountFcyColumnAtAll_importsCleanly() {
        when(organisationPublicApi.findByOrganisationId("org-events-single-funding")).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone("org-events-single-funding", "PROJ-X", "MS-X");

        // A FUNDING row with the "Amount FCY" column absent entirely (not just blank) — spend-detail
        // fields are only relevant to SPENDING events (FundingValidations.spendDetail), so this must
        // import cleanly.
        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount
                FUNDING,GRANT-SINGLE,,Cardano Foundation,USD,2026-07-01,,,,,,,,PROJ-X,MS-X,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "single-funding-no-amountfcy.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-single-funding")
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(1);
    }

    @Test
    void spendingEventSingleRow_missingCategoryVendorAmountColumns_reportsCleanValidationError() {
        when(organisationPublicApi.findByOrganisationId("org-events-single-spending")).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone("org-events-single-spending", "PROJ-Y", "MS-Y");

        // A SPENDING event with Category/Vendor/Amount FCY/FX Rate/Amount RCY columns entirely absent
        // from the header (not just blank) — these ARE required for SPENDING per FundingValidations.spendDetail,
        // and must fail with a clean 400-shaped row error, not an exception.
        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount
                SPENDING,GRANT-SINGLE-2,,,USD,2026-07-20,,,PROJ-Y,MS-Y,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "single-spending-missing-cols.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-single-spending")
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).containsExactly(
                "amountFcy, amountRcy, currencyRcy, currencyFcy and fxRate are required for SPENDING events");
        assertThat(result.getEventsCreated()).isZero();
    }

    @Test
    void duplicateMilestoneAllocationRowsInSameEvent_reportsCleanValidationErrorInsteadOfCrashing() {
        when(organisationPublicApi.findByOrganisationId("org-events-missing-amt")).thenReturn(Optional.of(new Organisation()));

        // Seed a project + milestone whose external ids literally match the ones referenced in the
        // CSV, so the error we see is caused by the CSV's own content (two identical rows allocating to
        // the same milestone), not by an unrelated "not found".
        seedProjectAndMilestone("org-events-missing-amt", "39bb7762-5cfb-430b-bfe7-b0982fc27316",
                "d3978bf7-e9b0-493c-b821-debdaee55708");

        MultipartFile file = new MockMultipartFile("file", "funding_events_missing_funding_amount_fcy.csv", "text/csv",
                EVENTS_DUPLICATE_MILESTONE_ALLOCATION_CSV.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-missing-amt")
                .files(List.of(file))
                .build();

        // Must not throw — SpendingEventService.populateNode now rejects the duplicate allocation with a
        // clean ProblemDetail before Hibernate ever sees two EventMilestoneAllocationEntity rows sharing
        // the same (eventId, milestoneId) id (which previously surfaced as an uncaught DuplicateKeyException).
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).containsExactly(
                "Duplicate allocation to the same milestone in this event: d3978bf7-e9b0-493c-b821-debdaee55708");
        assertThat(result.getEventsCreated()).isZero();
    }

    @Test
    void eventsCsv_projectTitleDoesNotResolveExternalProjectId_reportsNotFound() {
        String orgId = "org-events-title-not-resolved";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        // External Project ID / External Milestone ID are mandatory, exact-match natural keys —
        // referencing a project/milestone by its title instead must fail as not-found, even though the
        // title is a real, seeded value. externalProjectId/externalMilestoneId being opaque for
        // UI-created data is a frontend-side gap, not something the Events CSV importer papers over.
        seedProjectAndMilestone(orgId, "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");

        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount
                FUNDING,GRANT-TITLE,,Cardano Foundation,USD,2026-07-01,,,,,,,,,Seed Project,Seed Milestone,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "events-by-title.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).containsExactly("Project not found for externalProjectId: Seed Project");
        assertThat(result.getEventsCreated()).isZero();
    }

    /** Seeds PROJ-A/SUB-1 + MS-1/MS-2 exactly as the downloadable Projects/Milestones templates do. */
    private void seedProjectsAndMilestonesTemplate(String orgId) {
        MultipartFile projectsFile = new MockMultipartFile("file", "funding_projects_template.csv", "text/csv",
                PROJECTS_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult projResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(projectsFile)).build());
        assertThat(projResult.getFiles().get(0).getRowErrors()).as("seed projects").isEmpty();

        MultipartFile milestonesFile = new MockMultipartFile("file", "funding_milestones_template.csv", "text/csv",
                MILESTONES_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult msResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(milestonesFile)).build());
        assertThat(msResult.getFiles().get(0).getRowErrors()).as("seed milestones").isEmpty();
    }

    /** Seeds a root project and one milestone under it with the given external ids, via the real Projects/Milestones CSV path. */
    private void seedProjectAndMilestone(String orgId, String externalProjectId, String externalMilestoneId) {
        String projectsCsv = "External Project ID,Project Title,Funding ID,Total Amount,Currency,Parent External Project ID,Sub External Project ID,Sub Project Title,Sub Funding ID,Sub Total Amount,Sub Currency\n"
                + externalProjectId + ",Seed Project,,50000.00,USD,,,,,,\n";
        MultipartFile projectsFile = new MockMultipartFile("file", "seed-projects.csv", "text/csv", projectsCsv.getBytes());
        FundingBulkImportResult projResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(projectsFile)).build());
        assertThat(projResult.getFiles().get(0).getRowErrors()).as("seed project").isEmpty();

        String milestonesCsv = "External Project ID,External Milestone ID,Milestone Title,Milestone Amount,Currency,Milestone Date\n"
                + externalProjectId + "," + externalMilestoneId + ",Seed Milestone,10000.00,USD,2026-06-30\n";
        MultipartFile milestonesFile = new MockMultipartFile("file", "seed-milestones.csv", "text/csv", milestonesCsv.getBytes());
        FundingBulkImportResult msResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(milestonesFile)).build());
        assertThat(msResult.getFiles().get(0).getRowErrors()).as("seed milestone").isEmpty();
    }

    private static List<String> reasons(FundingBulkImportResult result) {
        return result.getFiles().get(0).getRowErrors().stream().map(FundingRowError::getReason).toList();
    }

    @Test
    void missingSubProjectTitleCsv_doesNotLeaveOrphanRootProjectBehind() {
        when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));

        MultipartFile file = new MockMultipartFile(
                "file", "missing-sub-project-title.csv", "text/csv", MISSING_SUB_PROJECT_TITLE_CSV.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(ORG_ID)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // The row error(s) are still reported...
        assertThat(result.getFiles()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isNotEmpty();
        assertThat(result.getProjectsCreated()).isZero();

        // ...and, crucially, nothing was actually left behind in the database: this is the part the
        // Mockito-based unit test cannot observe, since it has no real transaction to roll back.
        assertThat(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-E")).isEmpty();
    }

    /**
     * Sweeps all five "missing-*" cases. Only Funding ID is genuinely optional (never required for a
     * root project); the other four are all required fields on sub-project/root <em>create</em> and
     * correctly fail without persisting anything: Project Title and Sub Project Title always were
     * required, and Sub Total Amount / Sub Currency were tightened to match the REST API's stricter
     * flat {@code parentProjectId} shape ({@code ProjectWithMilestonesCreateRequest}: {@code totalAmount}
     * {@code @NotNull}, {@code currency} {@code @NotBlank}) — even though the API's own nested
     * {@code subProjects} shape ({@code ProjectTreeNodeRequest}) leaves both optional; the CSV path
     * deliberately picks the stricter of the two. Each case uses its own organisationId so the five
     * runs can't collide with each other in the shared database.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingFieldCsvCases")
    void missingFieldCsvFiles_behaveAsCurrentlyValidated(String caseName, String csvContent, String orgId,
            String externalProjectId, boolean expectSuccess) {
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        MultipartFile file = new MockMultipartFile("file", caseName + ".csv", "text/csv", csvContent.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);
        boolean projectPersisted = !projectRepository
                .findByOrganisationIdAndExternalProjectId(orgId, externalProjectId).isEmpty();

        if (expectSuccess) {
            assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
            assertThat(result.getProjectsCreated()).isEqualTo(1);
            assertThat(projectPersisted).as("%s: %s should be persisted", caseName, externalProjectId).isTrue();
        } else {
            assertThat(result.getFiles().get(0).getRowErrors()).isNotEmpty();
            assertThat(result.getProjectsCreated()).isZero();
            assertThat(projectPersisted).as("%s: %s should NOT be persisted", caseName, externalProjectId).isFalse();
        }
    }

    static Stream<Arguments> missingFieldCsvCases() {
        return Stream.of(
                arguments("missing-funding-id", MISSING_FUNDING_ID_CSV, "org-missing-funding-id", "PROJ-A", true),
                arguments("missing-project-title", MISSING_PROJECT_TITLE_CSV, "org-missing-project-title", "PROJ-A", false),
                arguments("missing-sub-currency", MISSING_SUB_CURRENCY_CSV, "org-missing-sub-currency", "PROJ-D", false),
                arguments("missing-sub-project-title", MISSING_SUB_PROJECT_TITLE_CSV, "org-missing-sub-project-title-2", "PROJ-E", false),
                arguments("missing-sub-total-amount", MISSING_SUB_TOTAL_AMOUNT_CSV, "org-missing-sub-total-amount", "PROJ-C", false)
        );
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "org.cardanofoundation.lob.app.funding",
            // AuthenticationUserService (MilestoneService, ProjectService, ...) and ClamAVService
            // (the AntiVirusScanner CsvParser needs). ClamAV defaults to disabled, so no real AV
            // daemon is needed.
            "org.cardanofoundation.lob.app.support.security",
            // CsvParser<T> must come from a component scan (not a @Bean factory method returning some
            // concrete CsvParser<Object>): Spring only does its lenient "unresolved generic matches any
            // requested parameterization" autowiring for a scanned class whose own generic is left
            // unresolved, not for a @Bean method whose declared return type fixes the parameter.
            "org.cardanofoundation.lob.app.organisation.service.csv"
    }, excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = EventPublishJob.class))
    // EventPublishJob is unrelated to the bulk-import flow under test and needs the *concrete*
    // OrganisationPublicApi (vs. everything else here, which needs the OrganisationPublicApiIF
    // interface) — mocking both would make every interface-typed injection point ambiguous.
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.lob.app.funding.repository")
    @EntityScan(basePackages = {
            "org.cardanofoundation.lob.app.funding.domain.entity",
            // RevInfoEntity: a custom @RevisionEntity with allocationSize=1 matching revinfo_seq's
            // actual DB increment — without it in scope, Envers falls back to DefaultRevisionEntity
            // (allocationSize=50), which mismatches the sequence and fails Hibernate SessionFactory
            // bootstrap.
            "org.cardanofoundation.lob.app.support.spring_audit.internal"
    })
    static class TestConfig {
    }
}
