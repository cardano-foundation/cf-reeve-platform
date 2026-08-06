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
import org.cardanofoundation.lob.app.funding.job.EventPublishJob;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.service.FundingBulkImportService;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;

/**
 * Real end-to-end regression test for the orphan-root-project fix, run against the actual CSV file
 * the bug was reported against (missing-sub-project-title.csv: a root project row whose only
 * sub-project row has no title, so sub-project creation fails). This boots the real, Spring-proxied
 * {@link FundingBulkImportService} bean chain against a real Postgres (Testcontainers) — the one
 * place in the suite that can observe the actual DB rollback performed by
 * {@code FundingProjectGroupTransactionRunner}. {@code FundingBulkImportServiceTest} runs outside a
 * Spring container, where its transaction runners are inert no-ops (there is no active transaction to
 * roll back), so it cannot prove the DB-level effect this test checks.
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
    }

    @Autowired
    private FundingBulkImportService bulkImportService;
    @Autowired
    private FundingProjectRepository projectRepository;
    @MockBean
    private OrganisationPublicApiIF organisationPublicApi;

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
