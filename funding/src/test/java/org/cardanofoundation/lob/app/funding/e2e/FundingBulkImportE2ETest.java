package org.cardanofoundation.lob.app.funding.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowError;
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.repository.MilestoneRepository;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Currency;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Real end-to-end regression tests for the bulk CSV importer, run against a real Postgres
 * (Testcontainers) through the actual, Spring-proxied {@link FundingBulkImportService} bean chain —
 * the one place in the suite that can observe real DB effects (persisted rows, absence of rows)
 * rather than mocked ones. {@code FundingBulkImportServiceTest} runs outside a Spring container,
 * where its transaction runner is an inert no-op, so it cannot prove DB-level behavior.
 *
 * <p>All CSV fixtures are embedded as string constants below rather than read from disk — earlier
 * versions of this test read them from the author's local {@code ~/Downloads}, which doesn't exist on
 * CI and made every one of these tests fail there.
 *
 * <p>Covers: every reference (project, sub-project, milestone) is by <b>title</b>, not by any
 * external/user-defined id; a root project's group is atomic — a failing sub-project or milestone row
 * anywhere in the group rolls back everything the group itself wrote (the root project, and any
 * sub-project or milestone from an earlier row in the same group), so a CSV group with one bad row
 * never leaves a partial project tree behind; a different, independent group (a different root
 * {@code Project Title}) earlier in the same file/request is unaffected; the Events file's
 * event-type-conditional column validation; and the duplicate-milestone-allocation crash fix.
 */
@SpringBootTest(classes = FundingBulkImportE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FundingBulkImportE2ETest {

    private static final String ORG_ID = "org1";

    private static final String PROJECTS_MILESTONES_TEMPLATE_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project A,100000.00,USD,Sub One,40000.00,Milestone One,20000.00,2026-06-30
            Project A,,,Sub One,,Milestone Two,20000.00,2026-07-15
            """;

    private static final String EVENTS_TEMPLATE_CSV = """
            Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
            FUNDING,GRANT-2025-001,,Cardano Foundation,USD,2026-07-01,,,,,,40000.00,,,Sub One,,Milestone One,20000.00
            FUNDING,GRANT-2025-001,,Cardano Foundation,USD,2026-07-01,,,,,,40000.00,,,Sub One,,Milestone Two,20000.00
            SPENDING,GRANT-2025-001,,,USD,2026-07-20,Personnel,Vendor AB,36000.00,EUR,0.9,40000.00,,Invoice #INV-001,Sub One,,Milestone One,20000.00
            SPENDING,GRANT-2025-001,,,USD,2026-07-20,Personnel,Vendor AB,36000.00,EUR,0.9,40000.00,,Invoice #INV-001,Sub One,,Milestone Two,20000.00
            """;

    private static final String EVENTS_DUPLICATE_MILESTONE_ALLOCATION_CSV = """
            Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
            FUNDING,GRANT-2025-Z,,Cardano Foundation,USD,2026-07-01,,,,,,2000.00,,,Dup Project,,Dup Milestone,2000.00
            FUNDING,GRANT-2025-Z,,Cardano Foundation,USD,2026-07-01,,,,,,2000.00,,,Dup Project,,Dup Milestone,2000.00
            """;

    // Root Project Title column entirely absent -> mandatory; the file fails to parse (a file-level
    // error via CsvParser.checkHeaders), nothing persisted.
    private static final String MISSING_PROJECT_TITLE_CSV = """
            Total Amount,Currency,Sub Project Title,Sub Total Amount
            100000.00,USD,Sub One,40000.00
            """;

    // Every column of the template is present in the header, but this row leaves Milestone Title/
    // Amount/Date blank (no milestone on this row) -> a sub-project always inherits the root's
    // currency (USD; there is no "Sub Currency" column in the template at all), and is created
    // successfully.
    private static final String MISSING_SUB_CURRENCY_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project D,100000.00,USD,Sub One,40000.00,,,
            """;

    // Sub Project Title blank, but Sub Total Amount filled -> this is an orphaned amount with no
    // title to attach it to (see ProjectMilestoneCsvLine#hasOrphanedSubProjectData), so the row
    // reports an error instead of silently discarding the amount; the root, being on an independent
    // row, still succeeds and no sub-project is created.
    private static final String BLANK_SUB_PROJECT_TITLE_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project E,100000.00,USD,,40000.00,,,
            """;

    // Sub Total Amount's header is present but its value is blank on this row -> required to create a
    // new sub-project; the sub-project row fails, the root is independent and still succeeds.
    private static final String MISSING_SUB_TOTAL_AMOUNT_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project C,100000.00,USD,Sub One,,,,
            """;

    // Root with one sub-project, which itself carries one milestone — seeds the tree used by the
    // currency-cascade regression test below. The root can't carry a milestone of its own here: a
    // project holds either milestones or sub-projects, never both.
    private static final String CASCADE_SEED_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project Cascade,100000.00,USD,Sub One,40000.00,Sub Milestone,20000.00,2026-06-30
            """;

    // Only the root row's Currency cell changes; Sub Project/Milestone columns are left entirely
    // blank, so nothing here directly touches the sub-project or its milestone — any change to them
    // can only come from the root's currency change cascading down.
    private static final String CASCADE_CURRENCY_UPDATE_CSV = """
            Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
            Project Cascade,100000.00,EUR,,,,,
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
    @Autowired
    private MilestoneRepository milestoneRepository;
    @MockitoBean
    private OrganisationPublicApiIF organisationPublicApi;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @BeforeEach
    void allowOrganisationAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
        // Currency codes referenced by these CSVs (USD, EUR, ...) are registered/active in the org's
        // currency table by default; currencyCode() checks this rather than java.util.Currency.
        Currency activeCurrency = new Currency(new Currency.Id("org1", "x"), "ISO_4217:x", true);
        lenient().when(organisationPublicApi.findCurrencyByCustomerCurrencyCode(anyString(), anyString()))
                .thenReturn(Optional.of(activeCurrency));
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
    void reUploadingTheSameProjectsMilestonesAndEventsFiles_isASafeNoOp_notAValidationFailure() {
        // Regression test for a real bug: re-uploading the exact same Projects+Milestones and Events
        // files twice used to fail the second time — the milestone update spuriously tripped
        // "amount below total already allocated" (each milestone accumulates allocations from both
        // the FUNDING and SPENDING event, so resending its own unchanged amount could look smaller
        // than that combined total), and the Events file had no update path at all (it always tried
        // to create, colliding with the event's own deterministic id on the second upload).
        String orgId = "org-reupload-idempotent";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        MultipartFile projectsFile1 = new MockMultipartFile("file", "funding_projects_milestones_template.csv", "text/csv",
                PROJECTS_MILESTONES_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult firstProjectsResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(projectsFile1)).build());
        assertThat(reasons(firstProjectsResult)).isEmpty();
        assertThat(firstProjectsResult.getProjectsCreated()).isEqualTo(2); // Project A + Sub One
        assertThat(firstProjectsResult.getMilestonesCreated()).isEqualTo(2); // Milestone One + Two

        MultipartFile eventsFile1 = new MockMultipartFile("file", "funding_events_template.csv", "text/csv",
                EVENTS_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult firstEventsResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(eventsFile1)).build());
        assertThat(reasons(firstEventsResult)).isEmpty();
        assertThat(firstEventsResult.getEventsCreated()).isEqualTo(2); // one FUNDING, one SPENDING
        assertThat(firstEventsResult.getAllocationsCreated()).isEqualTo(4); // 2 milestone rows per event
        assertThat(firstEventsResult.getAllocationsUpdated()).isZero();

        // Second upload of the identical files — must be a clean no-op update, not a failure.
        MultipartFile projectsFile2 = new MockMultipartFile("file", "funding_projects_milestones_template.csv", "text/csv",
                PROJECTS_MILESTONES_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult secondProjectsResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(projectsFile2)).build());
        assertThat(reasons(secondProjectsResult)).isEmpty();
        assertThat(secondProjectsResult.getProjectsCreated()).isZero();
        // Root (1) + Sub One resolved independently on each of its two rows (2) — sub-projects aren't
        // deduped across rows within a group, so the same sub-project can be legitimately counted more
        // than once when it's referenced by more than one row (here, once per milestone row).
        assertThat(secondProjectsResult.getProjectsUpdated()).isEqualTo(3);
        assertThat(secondProjectsResult.getMilestonesCreated()).isZero();
        assertThat(secondProjectsResult.getMilestonesUpdated()).isEqualTo(2);

        MultipartFile eventsFile2 = new MockMultipartFile("file", "funding_events_template.csv", "text/csv",
                EVENTS_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult secondEventsResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(eventsFile2)).build());
        assertThat(reasons(secondEventsResult)).isEmpty();
        assertThat(secondEventsResult.getEventsCreated()).isZero();
        assertThat(secondEventsResult.getEventsUpdated()).isEqualTo(2);
        // Re-upload replaces each event's allocations wholesale — they are updates, not new allocations.
        assertThat(secondEventsResult.getAllocationsCreated()).isZero();
        assertThat(secondEventsResult.getAllocationsUpdated()).isEqualTo(4);
    }

    @Test
    void reUploadWithAGenuinelyChangedMilestoneAmount_isStillValidatedCorrectly() {
        // The idempotent-resend fix must not silently swallow *real* changes — an actual amount
        // change still has to pass FundingValidations for real (it just shouldn't be checked when
        // nothing changed).
        String orgId = "org-reupload-real-change";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone(orgId, "Change Project", "Change Milestone"); // amount 10000.00

        // Fund + spend the milestone's full 10000.00 so it has a real allocation on record.
        String eventsCsv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                FUNDING,GRANT-CHANGE,,Cardano Foundation,USD,2026-07-01,,,,,,10000.00,,,Change Project,,Change Milestone,10000.00
                """;
        MultipartFile eventsFile = new MockMultipartFile("file", "fund.csv", "text/csv", eventsCsv.getBytes());
        FundingBulkImportResult fundResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(eventsFile)).build());
        assertThat(reasons(fundResult)).isEmpty();

        // Attempting to shrink the milestone below its already-allocated 10000.00 must still fail —
        // this is a real change, not a same-value resend. Total Amount/Currency/Sub Project
        // Title/Sub Total Amount are present in the header but left blank: this row only touches the
        // milestone, not the root project.
        String shrinkCsv = """
                Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
                Change Project,,,,,Change Milestone,5000.00,2026-06-30
                """;
        MultipartFile shrinkFile = new MockMultipartFile("file", "shrink.csv", "text/csv", shrinkCsv.getBytes());
        FundingBulkImportResult shrinkResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(shrinkFile)).build());

        assertThat(reasons(shrinkResult)).containsExactly(
                "Milestone amount 5000.00 is below the total already allocated to it 10000.0000000000. "
                        + "All changes for project \"Change Project\" in this request were rolled back because of this error.");
        assertThat(shrinkResult.getMilestonesUpdated()).isZero();
    }

    @Test
    void rootCurrencyUpdate_cascadesToSubProjectAndItsMilestone() {
        // Regression test: updating a root project's currency via CSV used to leave descendant
        // sub-projects — and, transitively, their milestones — stuck on the old currency, even though
        // the root project's own record was correctly updated.
        String orgId = "org-currency-cascade";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        MultipartFile seedFile = new MockMultipartFile("file", "seed.csv", "text/csv", CASCADE_SEED_CSV.getBytes());
        FundingBulkImportResult seedResult = bulkImportService.importFiles(
                BulkImportRequest.builder().organisationId(orgId).files(List.of(seedFile)).build());
        assertThat(reasons(seedResult)).as("seed cascade tree").isEmpty();

        MultipartFile updateFile = new MockMultipartFile("file", "update.csv", "text/csv", CASCADE_CURRENCY_UPDATE_CSV.getBytes());
        FundingBulkImportResult updateResult = bulkImportService.importFiles(
                BulkImportRequest.builder().organisationId(orgId).files(List.of(updateFile)).build());

        assertThat(reasons(updateResult)).isEmpty();

        ProjectEntity root = projectRepository
                .findByOrganisationIdAndProjectTitleAndParentProjectIsNull(orgId, "Project Cascade").orElseThrow();
        ProjectEntity subProject = projectRepository.findByOrganisationIdAndProjectTitle(orgId, "Sub One")
                .stream().findFirst().orElseThrow();

        assertThat(root.getCurrency()).isEqualTo("EUR");
        assertThat(subProject.getCurrency()).as("sub-project currency must follow the root's").isEqualTo("EUR");
        assertThat(milestoneRepository.findByProjectIdAndMilestoneTitle(subProject.getId(), "Sub Milestone").orElseThrow().getCurrency())
                .as("sub-project's milestone currency must follow its (now-updated) project's")
                .isEqualTo("EUR");
    }

    @Test
    void fundingEventSingleRow_blankAmountFcyValue_importsCleanly() {
        when(organisationPublicApi.findByOrganisationId("org-events-single-funding")).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone("org-events-single-funding", "Seed Project X", "Seed Milestone X");

        // Every column of the template is present in the header, but a FUNDING row leaves "Amount FCY"
        // blank — the spend-only fields (category/vendor/amountFcy/currencyFcy/fxRate/hash/notes) are
        // only relevant to SPENDING events (FundingValidations.spendDetail), so this must import
        // cleanly. Amount RCY, however, is required for every event type and must equal the row's
        // allocated amount (2000.00).
        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                FUNDING,GRANT-SINGLE,,Cardano Foundation,USD,2026-07-01,,,,,,2000.00,,,Seed Project X,,Seed Milestone X,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "single-funding-blank-amountfcy.csv", "text/csv", csv.getBytes());

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
        seedProjectAndMilestone("org-events-single-spending", "Seed Project Y", "Seed Milestone Y");

        // Every column of the template is present in the header, but a SPENDING event leaves
        // Category/Vendor/Amount FCY/Currency FCY/FX Rate/Amount RCY blank — these ARE required for
        // SPENDING per FundingValidations.spendDetail, and must fail with a clean 400-shaped row error,
        // not an exception.
        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                SPENDING,GRANT-SINGLE-2,,,USD,2026-07-20,,,,,,,,,Seed Project Y,,Seed Milestone Y,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "single-spending-blank-cols.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-single-spending")
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Currency RCY is present ("USD") — the message must name only the fields that are actually missing.
        assertThat(reasons(result)).containsExactly(
                "Missing required field(s) for a SPENDING event: amountFcy, amountRcy, currencyFcy, fxRate");
        assertThat(result.getEventsCreated()).isZero();
    }

    @Test
    void duplicateMilestoneAllocationRowsInSameEvent_reportsCleanValidationErrorInsteadOfCrashing() {
        when(organisationPublicApi.findByOrganisationId("org-events-dup-alloc")).thenReturn(Optional.of(new Organisation()));

        // Seed a project + milestone whose titles literally match the ones referenced in the CSV, so
        // the error we see is caused by the CSV's own content (two identical rows allocating to the
        // same milestone), not by an unrelated "not found".
        seedProjectAndMilestone("org-events-dup-alloc", "Dup Project", "Dup Milestone");

        MultipartFile file = new MockMultipartFile("file", "funding_events_duplicate_allocation.csv", "text/csv",
                EVENTS_DUPLICATE_MILESTONE_ALLOCATION_CSV.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId("org-events-dup-alloc")
                .files(List.of(file))
                .build();

        // Must not throw — SpendingEventService.populateNode now rejects the duplicate allocation with a
        // clean ProblemDetail before Hibernate ever sees two EventMilestoneAllocationEntity rows sharing
        // the same (eventId, milestoneId) id (which previously surfaced as an uncaught DuplicateKeyException).
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).containsExactly(
                "Duplicate allocation to the same milestone in this event: Dup Milestone");
        assertThat(result.getEventsCreated()).isZero();
    }

    @Test
    void eventsCsv_unknownProjectTitle_reportsNotFound() {
        String orgId = "org-events-unknown-title";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));
        seedProjectAndMilestone(orgId, "Seed Project Z", "Seed Milestone Z");

        String csv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                FUNDING,GRANT-TITLE,,Cardano Foundation,USD,2026-07-01,,,,,,2000.00,,,Never Seeded Project,,Seed Milestone Z,2000.00
                """;
        MultipartFile file = new MockMultipartFile("file", "events-unknown-title.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(reasons(result)).containsExactly("Project not found for projectTitle: Never Seeded Project");
        assertThat(result.getEventsCreated()).isZero();
    }

    @Test
    void eventsCsv_sameSubProjectTitleUnderTwoRoots_ambiguousWithoutDisambiguation_resolvedWithSubProjectTitle() {
        // Two different roots each have a sub-project titled "Twin Sub" — a bare Project Title
        // reference to "Twin Sub" alone cannot tell which one is meant. Root X's Twin Sub and Root Y's
        // Twin Sub deliberately have differently-named milestones, so a correct resolution to Root Y's
        // Twin Sub is provable: if disambiguation picked the wrong one (Root X's), "Milestone One"
        // wouldn't exist under it and the import would fail with "milestone not found" instead of
        // succeeding.
        String orgId = "org-events-subproject-disambiguation";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        String seedRootX = """
                Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
                Root X,50000.00,USD,Twin Sub,50000.00,Wrong Milestone,10000.00,2026-06-30
                """;
        String seedRootY = """
                Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
                Root Y,50000.00,USD,Twin Sub,50000.00,Milestone One,10000.00,2026-06-30
                """;
        FundingBulkImportResult seedXResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(new MockMultipartFile("file", "x.csv", "text/csv", seedRootX.getBytes()))).build());
        assertThat(reasons(seedXResult)).isEmpty();
        FundingBulkImportResult seedYResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(new MockMultipartFile("file", "y.csv", "text/csv", seedRootY.getBytes()))).build());
        assertThat(reasons(seedYResult)).isEmpty();

        // Without Sub Project Title: ambiguous, since "Twin Sub" alone matches two projects.
        String ambiguousCsv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                FUNDING,GRANT-AMBIG,,Cardano Foundation,USD,2026-07-01,,,,,,10000.00,,,Twin Sub,,Milestone One,10000.00
                """;
        MultipartFile ambiguousFile = new MockMultipartFile("file", "ambiguous.csv", "text/csv", ambiguousCsv.getBytes());
        FundingBulkImportResult ambiguousResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(ambiguousFile)).build());
        assertThat(reasons(ambiguousResult)).hasSize(1);
        assertThat(reasons(ambiguousResult).get(0)).contains("Twin Sub").contains("more than one project");
        assertThat(ambiguousResult.getEventsCreated()).isZero();

        // With Root Y named via Project Title and Twin Sub named via Sub Project Title: resolved
        // deterministically to Root Y's Twin Sub, where "Milestone One" actually exists.
        String disambiguatedCsv = """
                Event Type,Funding ID,Funding Hash,Funding Entity,Currency RCY,Event Date,Category,Vendor,Amount FCY,Currency FCY,FX Rate,Amount RCY,Hash,Notes,Project Title,Sub Project Title,Milestone Title,Allocated Amount
                FUNDING,GRANT-DISAMBIG,,Cardano Foundation,USD,2026-07-01,,,,,,10000.00,,,Root Y,Twin Sub,Milestone One,10000.00
                """;
        MultipartFile disambiguatedFile = new MockMultipartFile("file", "disambiguated.csv", "text/csv", disambiguatedCsv.getBytes());
        FundingBulkImportResult disambiguatedResult = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(disambiguatedFile)).build());

        assertThat(reasons(disambiguatedResult)).isEmpty();
        assertThat(disambiguatedResult.getEventsCreated()).isEqualTo(1);
    }

    /** Seeds Project A/Sub One + Milestone One/Two exactly as the downloadable template does. */
    private void seedProjectsAndMilestonesTemplate(String orgId) {
        MultipartFile file = new MockMultipartFile("file", "funding_projects_milestones_template.csv", "text/csv",
                PROJECTS_MILESTONES_TEMPLATE_CSV.getBytes());
        FundingBulkImportResult result = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(file)).build());
        assertThat(result.getFiles().get(0).getRowErrors()).as("seed projects+milestones").isEmpty();
    }

    /** Seeds a root project and one milestone directly on it, via the real Projects+Milestones CSV path. */
    private void seedProjectAndMilestone(String orgId, String projectTitle, String milestoneTitle) {
        String csv = "Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date\n"
                + projectTitle + ",50000.00,USD,,," + milestoneTitle + ",10000.00,2026-06-30\n";
        MultipartFile file = new MockMultipartFile("file", "seed.csv", "text/csv", csv.getBytes());
        FundingBulkImportResult result = bulkImportService.importFiles(BulkImportRequest.builder()
                .organisationId(orgId).files(List.of(file)).build());
        assertThat(result.getFiles().get(0).getRowErrors()).as("seed project+milestone").isEmpty();
    }

    private static List<String> reasons(FundingBulkImportResult result) {
        return result.getFiles().get(0).getRowErrors().stream().map(FundingRowError::getReason).toList();
    }

    @Test
    void subProjectRowFailure_rollsBackTheRootFromTheSameGroup() {
        // A root's own columns are never split across a different row than its sub-project's (this
        // format's row shape makes an "orphan root" impossible by construction) — but the group as a
        // whole is atomic: a failing sub-project row still rolls back the root that was
        // resolved/created earlier in the very same group, so nothing is left half-built. The CSV can
        // be fixed and safely re-uploaded in full.
        when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));

        MultipartFile file = new MockMultipartFile(
                "file", "missing-sub-total-amount.csv", "text/csv", MISSING_SUB_TOTAL_AMOUNT_CSV.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(ORG_ID)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // The sub-project row error is still reported...
        assertThat(result.getFiles()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isNotEmpty();
        // ...and now the root's own write is rolled back along with it — this is the part the
        // Mockito-based unit test cannot observe, since it has no real transaction to roll back.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project C"))
                .isEmpty();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One")).isEmpty();
    }

    @Test
    void secondSubProjectExceedingParentBudget_rollsBackTheRootAndTheFirstSubProjectAndItsMilestone() {
        // Reproduces the reported bug exactly: a root project, then two sub-projects each with one
        // milestone, where only the second sub-project fails validation (its total, combined with the
        // first sub-project's, exceeds the parent's budget). Before this fix, the root, the first
        // sub-project, and its milestone were left permanently persisted even though the request as a
        // whole reported a failure — this proves the whole group is now discarded together.
        String orgId = "org-cascade-budget-rollback";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        String csv = """
                Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
                Project Cascade,200,CHF,,,,,
                Project Cascade,,,Sub 1,190,Milestone 1,190,2026-10-08
                Project Cascade,,,Sub 2,190,Milestone 1,190,2026-09-24
                """;
        MultipartFile file = new MockMultipartFile("file", "funding-project_milestone.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        String reason = result.getFiles().get(0).getRowErrors().get(0).getReason();
        // The message must make the rollback itself visible, not just the validation failure — otherwise
        // there's no hint that row 2's already-succeeded sub-project and milestone were undone too.
        assertThat(reason)
                .contains("exceeds the parent project total")
                .contains("rolled back")
                .contains("Project Cascade");
        // Nothing from the group survives — not the root, not the first (successful-on-its-own)
        // sub-project, not its milestone.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(orgId, "Project Cascade"))
                .isEmpty();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitle(orgId, "Sub 1")).isEmpty();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitle(orgId, "Sub 2")).isEmpty();
    }

    @Test
    void midGroupSubProjectAmountChange_rollsBackTheEarlierCleanMilestoneItKnockedOver() {
        // Reproduces the JIRA-reported edge case exactly: a project with one sub-project and three
        // milestone rows, where:
        //  - row 1 (Milestone One) fails outright: its Sub One amount of 150000 exceeds the parent's
        //    total of 100000, so Sub One isn't created yet on this row
        //  - row 2 (Milestone Two) is entirely clean on its own: Sub One (50000, within budget) gets
        //    created here instead, and Milestone Two (20000) fits under it
        //  - row 3 (Milestone Three) is also individually valid data, but it re-supplies a *different*
        //    Sub One amount (30000) than row 2 did — a legitimate-looking update — which knocks Sub
        //    One's budget below what Milestone Two and Milestone Three together now need (45000 over
        //    a 30000 budget), so Milestone Three's own creation fails
        // Before the group-atomicity fix, this produced exactly the reported bug: Project Test, Sub One
        // (at the amount row 3 silently changed it to), and Milestone Two were left permanently
        // persisted, Milestone Three was missing, and there was no direct signal that Sub One's amount
        // had been mutated as a side effect — a state re-uploading the file couldn't cleanly fix. Now
        // the whole group — root, Sub One, and Milestone Two included — must roll back together.
        String orgId = "org-midgroup-subamount-change";
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        String csv = """
                Project Title,Total Amount,Currency,Sub Project Title,Sub Total Amount,Milestone Title,Milestone Amount,Milestone Date
                Project Test,100000.00,USD,,,,,
                Project Test,,,Sub One,150000.00,Milestone One,20000.00,2026-06-30
                Project Test,,,Sub One,50000.00,Milestone Two,20000.00,2026-07-15
                Project Test,,,Sub One,30000.00,Milestone Three,25000.00,2026-08-01
                """;
        MultipartFile file = new MockMultipartFile("file", "funding-project_test_3_milestones.csv", "text/csv", csv.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Both underlying issues are reported, and every one of them is annotated with the rollback
        // note for this project's group.
        assertThat(result.getFiles()).hasSize(1);
        List<String> reasons = reasons(result);
        assertThat(reasons)
                .hasSize(2)
                .anySatisfy(reason -> assertThat(reason).contains("exceeds the parent project total"))
                .anySatisfy(reason -> assertThat(reason).contains("exceeds the project total"))
                .allSatisfy(reason -> assertThat(reason).contains("rolled back").contains("Project Test"));
        // Nothing from the group survives — not the root, not Sub One (regardless of which amount it
        // was ever set to), not Milestone Two, which was individually clean and would have persisted
        // under the old, row-independent behavior.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(orgId, "Project Test"))
                .isEmpty();
        assertThat(projectRepository.findByOrganisationIdAndProjectTitle(orgId, "Sub One")).isEmpty();
    }

    /**
     * Sweeps the "missing-*"/edge-case fixtures for the merged Projects+Milestones row shape. Every
     * fixture's header carries the full template — only the {@code value} in a given cell is missing
     * (blank), never the column itself (a column absent from the header row is now a file-level error,
     * see {@link FundingCsvTypeDetector#missingHeaders}). There is no "Sub Currency" column in the
     * template at all — a sub-project always defaults to the root's own currency; Project Title is
     * mandatory (its absence fails to parse at all); Sub Total Amount is required to <em>create</em> a
     * new sub-project, and a blank Sub Project Title with a Sub Total Amount present is an orphaned
     * amount with nothing to attach it to — both are row errors that now roll back the root too, since
     * a group is all-or-nothing.
     * Each case uses its own organisationId so the runs can't collide with each other in the shared
     * database.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeCaseCsvCases")
    void edgeCaseCsvFiles_behaveAsCurrentlyValidated(String caseName, String csvContent, String orgId,
            String rootProjectTitle, boolean expectRootSuccess, boolean expectSubProjectCreated, boolean expectAnyError) {
        when(organisationPublicApi.findByOrganisationId(orgId)).thenReturn(Optional.of(new Organisation()));

        MultipartFile file = new MockMultipartFile("file", caseName + ".csv", "text/csv", csvContent.getBytes());

        BulkImportRequest request = BulkImportRequest.builder()
                .organisationId(orgId)
                .files(List.of(file))
                .build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);
        boolean rootPersisted = projectRepository
                .findByOrganisationIdAndProjectTitleAndParentProjectIsNull(orgId, rootProjectTitle).isPresent();
        boolean subProjectPersisted = !projectRepository.findByOrganisationIdAndProjectTitle(orgId, "Sub One").isEmpty();

        if (expectRootSuccess) {
            assertThat(rootPersisted).as("%s: root %s should be persisted", caseName, rootProjectTitle).isTrue();
            assertThat(result.getProjectsCreated()).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(rootPersisted).as("%s: root %s should NOT be persisted", caseName, rootProjectTitle).isFalse();
            assertThat(result.getProjectsCreated()).isZero();
        }
        assertThat(subProjectPersisted).as("%s: Sub One created?", caseName).isEqualTo(expectSubProjectCreated);
        if (expectAnyError) {
            assertThat(result.getFiles().get(0).getRowErrors()).as("%s: expected a row/file error", caseName).isNotEmpty();
        } else {
            assertThat(result.getFiles().get(0).getRowErrors()).as("%s: expected no error", caseName).isEmpty();
        }
    }

    static Stream<Arguments> edgeCaseCsvCases() {
        return Stream.of(
                arguments("missing-project-title", MISSING_PROJECT_TITLE_CSV, "org-missing-project-title", "Project A", false, false, true),
                arguments("missing-sub-currency", MISSING_SUB_CURRENCY_CSV, "org-missing-sub-currency", "Project D", true, true, false),
                // A blank Sub Project Title with a Sub Total Amount present is an orphaned amount —
                // reported as a row error, not silently dropped — which now rolls back the root too
                // (a group is all-or-nothing).
                arguments("blank-sub-project-title", BLANK_SUB_PROJECT_TITLE_CSV, "org-blank-sub-project-title", "Project E", false, false, true),
                arguments("missing-sub-total-amount", MISSING_SUB_TOTAL_AMOUNT_CSV, "org-missing-sub-total-amount", "Project C", false, false, true)
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
