package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

/**
 * Real end-to-end regression tests for the bulk CSV importer, run against a real Postgres
 * (Testcontainers) through the actual, Spring-proxied {@link FundingBulkImportService} bean chain —
 * the one place in the suite that can observe real DB effects (rollback, persisted rows) rather than
 * mocked ones. {@code FundingBulkImportServiceTest} runs outside a Spring container, where its
 * transaction runners are inert no-ops, so it cannot prove DB-level behavior.
 *
 * <p>Covers: the orphan-root-project rollback fix (missing-sub-project-title.csv), the Events file's
 * event-type-conditional column validation, the duplicate-milestone-allocation crash fix, and that
 * External Project ID / External Milestone ID stay strict, exact-match references (a project's title
 * is not an acceptable substitute, even when the title is real and seeded).
 */
@SpringBootTest(classes = FundingBulkImportE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingBulkImportE2ETest {

    private static final String ORG_ID = "org1";
    private static final Path MISSING_SUB_PROJECT_TITLE_CSV = Path.of("/home/mrusso/Downloads/missing-sub-project-title.csv");

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
        // MilestoneService.createMilestone/updateMilestone gate on KeycloakSecurityHelper.canUserAccessOrg,
        // which needs a JWT Authentication in the SecurityContext (present on a real authenticated
        // request through the controller, absent here). Disabling Keycloak makes canUserAccessOrg a
        // no-op pass-through, matching what a real authenticated caller would already satisfy.
        registry.add("keycloak.enabled", () -> "false");
    }

    @Autowired
    private FundingBulkImportService bulkImportService;
    @Autowired
    private FundingProjectRepository projectRepository;
    @MockBean
    private OrganisationPublicApiIF organisationPublicApi;

    @Test
    void eventsTemplateCsv_seededWithMatchingProjectsAndMilestones_importsCleanly() throws IOException {
        when(organisationPublicApi.findByOrganisationId("org-events-good")).thenReturn(Optional.of(new Organisation()));
        seedProjectsAndMilestonesTemplate("org-events-good");

        byte[] csvBytes = Files.readAllBytes(Path.of("/home/mrusso/Downloads/funding_events_template (2).csv"));
        MultipartFile file = new MockMultipartFile("file", "funding_events_template (2).csv", "text/csv", csvBytes);

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-good")
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(2);
    }

    @Test
    void fundingEventSingleRow_noAmountFcyColumnAtAll_importsCleanly() throws IOException {
        when(organisationPublicApi.findByOrganisationId("org-events-single-funding")).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone("org-events-single-funding", "PROJ-X", "MS-X");

        // A FUNDING row with the "Amount FCY" column absent entirely (not just blank) — spend-detail
        // fields are only relevant to SPENDING events (FundingValidations.spendDetail), so this must
        // import cleanly.
        String csv = "Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount\n"
                + "FUNDING,GRANT-SINGLE,,Cardano Foundation,USD,2026-07-01,,,,,,,,PROJ-X,MS-X,2000.00\n";
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
    void spendingEventSingleRow_missingCategoryVendorAmountColumns_reportsCleanValidationError() throws IOException {
        when(organisationPublicApi.findByOrganisationId("org-events-single-spending")).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone("org-events-single-spending", "PROJ-Y", "MS-Y");

        // A SPENDING event with Category/Vendor/Amount FCY/FX Rate/Amount RCY columns entirely absent
        // from the header (not just blank) — these ARE required for SPENDING per FundingValidations.spendDetail,
        // and must fail with a clean 400-shaped row error, not an exception.
        String csv = "Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount\n"
                + "SPENDING,GRANT-SINGLE-2,,,USD,2026-07-20,,,PROJ-Y,MS-Y,2000.00\n";
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
    void duplicateMilestoneAllocationRowsInSameEvent_reportsCleanValidationErrorInsteadOfCrashing() throws IOException {
        when(organisationPublicApi.findByOrganisationId("org-events-missing-amt")).thenReturn(Optional.of(new Organisation()));

        // Seed a project + milestone whose external ids literally match the ones referenced in the
        // CSV, so the error we see is caused by the CSV's own content (two identical rows allocating to
        // the same milestone), not by an unrelated "not found".
        seedProjectAndMilestone("org-events-missing-amt", "39bb7762-5cfb-430b-bfe7-b0982fc27316",
                "d3978bf7-e9b0-493c-b821-debdaee55708");

        byte[] csvBytes = Files.readAllBytes(Path.of("/home/mrusso/Downloads/funding_events_missing_funding_amount_fcy.csv"));
        MultipartFile file = new MockMultipartFile("file", "funding_events_missing_funding_amount_fcy.csv", "text/csv", csvBytes);

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
    void eventsCsv_projectTitleDoesNotResolveExternalProjectId_reportsNotFound() throws IOException {
        String orgId = "org-events-title-not-resolved";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        // External Project ID / External Milestone ID are mandatory, exact-match natural keys —
        // referencing a project/milestone by its title instead must fail as not-found, even though the
        // title is a real, seeded value. externalProjectId/externalMilestoneId being opaque for
        // UI-created data is a frontend-side gap, not something the Events CSV importer papers over.
        seedProjectAndMilestone(orgId, "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");

        String csv = "Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,External Project ID,External Milestone ID,Allocated Amount\n"
                + "FUNDING,GRANT-TITLE,,Cardano Foundation,USD,2026-07-01,,,,,,,,,Seed Project,Seed Milestone,2000.00\n";
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
    private void seedProjectsAndMilestonesTemplate(String orgId) throws IOException {
        MultipartFile projectsFile = new MockMultipartFile("file", "funding_projects_template.csv", "text/csv",
                Files.readAllBytes(Path.of("/home/mrusso/Downloads/funding_projects_template.csv")));
        FundingBulkImportResult projResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(projectsFile)).build());
        assertThat(projResult.getFiles().get(0).getRowErrors()).as("seed projects").isEmpty();

        MultipartFile milestonesFile = new MockMultipartFile("file", "funding_milestones_template.csv", "text/csv",
                Files.readAllBytes(Path.of("/home/mrusso/Downloads/funding_milestones_template.csv")));
        FundingBulkImportResult msResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(milestonesFile)).build());
        assertThat(msResult.getFiles().get(0).getRowErrors()).as("seed milestones").isEmpty();
    }

    /** Seeds a root project and one milestone under it with the given external ids, via the real Projects/Milestones CSV path. */
    private void seedProjectAndMilestone(String orgId, String externalProjectId, String externalMilestoneId) throws IOException {
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
    void missingSubProjectTitleCsv_doesNotLeaveOrphanRootProjectBehind() throws IOException {
        when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));

        byte[] csvBytes = Files.readAllBytes(MISSING_SUB_PROJECT_TITLE_CSV);
        MultipartFile file = new MockMultipartFile(
                "file", "missing-sub-project-title.csv", "text/csv", csvBytes);

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
     * Sweeps all five "missing-*.csv" sample files. Only Funding ID is genuinely optional (never
     * required for a root project); the other four are all required fields on sub-project/root
     * <em>create</em> and correctly fail without persisting anything: Project Title and Sub Project
     * Title always were required, and Sub Total Amount / Sub Currency were tightened to match the
     * REST API's flat {@code parentProjectId} shape ({@code ProjectWithMilestonesCreateRequest}:
     * {@code totalAmount} {@code @NotNull}, {@code currency} {@code @NotBlank}) — even though the
     * API's own nested {@code subProjects} shape ({@code ProjectTreeNodeRequest}) leaves both
     * optional; the CSV path deliberately picks the stricter of the two. Each case uses its own
     * organisationId so the five runs can't collide with each other in the shared database.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("missingFieldCsvCases")
    void missingFieldCsvFiles_behaveAsCurrentlyValidated(String csvFileName, String orgId,
            String externalProjectId, boolean expectSuccess) throws IOException {
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        byte[] csvBytes = Files.readAllBytes(Path.of("/home/mrusso/Downloads/" + csvFileName));
        MultipartFile file = new MockMultipartFile("file", csvFileName, "text/csv", csvBytes);

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
            assertThat(projectPersisted).as("%s: %s should be persisted", csvFileName, externalProjectId).isTrue();
        } else {
            assertThat(result.getFiles().get(0).getRowErrors()).isNotEmpty();
            assertThat(result.getProjectsCreated()).isZero();
            assertThat(projectPersisted).as("%s: %s should NOT be persisted", csvFileName, externalProjectId).isFalse();
        }
    }

    static Stream<Arguments> missingFieldCsvCases() {
        return Stream.of(
                // Funding ID missing -> optional, row succeeds.
                arguments("missing-funding-id.csv", "org-missing-funding-id", "PROJ-A", true),
                // Root Project Title missing -> required; fails before any persistence (pre-existing
                // behavior, not part of the orphan-root fix).
                arguments("missing-project-title.csv", "org-missing-project-title", "PROJ-A", false),
                // Sub Currency missing -> now required to match the REST API's stricter shape; fails,
                // root rolled back as an orphan (no surviving sub-project).
                arguments("missing-sub-currency.csv", "org-missing-sub-currency", "PROJ-D", false),
                // Sub Project Title missing -> required; this is the orphan-root case the fix covers.
                arguments("missing-sub-project-title.csv", "org-missing-sub-project-title-2", "PROJ-E", false),
                // Sub Total Amount missing -> now required to match the REST API's stricter shape;
                // fails, root rolled back as an orphan.
                arguments("missing-sub-total-amount.csv", "org-missing-sub-total-amount", "PROJ-C", false)
        );
    }

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {
            "org.cardanofoundation.lob.app.funding",
            // KeycloakSecurityHelper/AuthenticationUserService (MilestoneService, ProjectService, ...)
            // and ClamAVService (the AntiVirusScanner CsvParser needs) — all trivially constructible
            // with their defaults (keycloak.enabled defaults true but only gates a runtime check, not
            // construction; clamav.enabled defaults false so no real AV daemon is needed).
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
