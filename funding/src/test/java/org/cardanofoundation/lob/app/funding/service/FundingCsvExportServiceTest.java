package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.opencsv.CSVReader;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@ExtendWith(MockitoExtension.class)
class FundingCsvExportServiceTest {

    private static final String ORG_ID = "org1";
    private static final Pageable PAGEABLE = PageRequest.of(0, 10);

    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private KeycloakSecurityHelper keycloakSecurityHelper;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    private FundingCsvExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new FundingCsvExportService(projectRepository, milestoneService, keycloakSecurityHelper, organisationPublicApi);
        lenient().when(keycloakSecurityHelper.canUserAccessOrg(ORG_ID)).thenReturn(true);
        lenient().when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));
    }

    // -------------------------------------------------------------------------
    // validateExport
    // -------------------------------------------------------------------------

    @Test
    void validateExport_returnsUnauthorized_whenUserCannotAccessOrg() {
        when(keycloakSecurityHelper.canUserAccessOrg(ORG_ID)).thenReturn(false);

        Optional<ProblemDetail> error = exportService.validateExport(ORG_ID);

        assertThat(error).isPresent();
        assertThat(error.get().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void validateExport_returnsNotFound_whenOrganisationDoesNotExist() {
        when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.empty());

        Optional<ProblemDetail> error = exportService.validateExport(ORG_ID);

        assertThat(error).isPresent();
        assertThat(error.get().getTitle()).isEqualTo(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
    }

    @Test
    void validateExport_returnsEmpty_whenAuthorizedAndOrganisationExists() {
        assertThat(exportService.validateExport(ORG_ID)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // writeProjectsMilestonesExport — row shape
    // -------------------------------------------------------------------------

    @Test
    void writeExport_writesHeaderRow_evenWithNoProjects() throws Exception {
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of()));

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly("Project Title", "Project ID", "Total Amount", "Currency",
                "Sub Project Title", "Sub Project ID", "Sub Total Amount",
                "Milestone Title", "Milestone ID", "Milestone Amount", "Milestone Date");
    }

    @Test
    void writeExport_rootWithNoChildren_getsOneRow_ownColumnsOnly() throws Exception {
        ProjectEntity root = rootProject("p1", "Project A", "PRJ-1", "100000.00", "USD");
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of());

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(2); // header + the one row
        assertThat(rows.get(1)).containsExactly("Project A", "PRJ-1", "100000.00", "USD", "", "", "", "", "", "", "");
    }

    @Test
    void writeExport_rootWithDirectMilestones_getsOneRowPerMilestone() throws Exception {
        ProjectEntity root = rootProject("p1", "Project B", "PRJ-2", "50000.00", "EUR");
        MilestoneEntity m1 = milestone("m1", "Milestone One", "PRJ-2-1", "20000.00", LocalDate.of(2026, 6, 30));
        MilestoneEntity m2 = milestone("m2", "Milestone Two", "PRJ-2-2", "30000.00", LocalDate.of(2026, 7, 15));
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of(m1, m2));

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(3); // header + 2 milestone rows
        assertThat(rows.get(1)).containsExactly("Project B", "PRJ-2", "50000.00", "EUR", "", "", "",
                "Milestone One", "PRJ-2-1", "20000.00", "2026-06-30");
        assertThat(rows.get(2)).containsExactly("Project B", "PRJ-2", "50000.00", "EUR", "", "", "",
                "Milestone Two", "PRJ-2-2", "30000.00", "2026-07-15");
    }

    @Test
    void writeExport_subProjectWithNoMilestonesYet_stillGetsItsOwnRow() throws Exception {
        ProjectEntity root = rootProject("p1", "Project C", "PRJ-3", "100000.00", "USD");
        ProjectEntity sub = subProject("s1", "Sub One", "PRJ-3-1", "40000.00", root);
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(sub));
        when(milestoneService.findByProjectId("s1")).thenReturn(List.of());

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("Project C", "PRJ-3", "100000.00", "USD",
                "Sub One", "PRJ-3-1", "40000.00", "", "", "", "");
        verify(milestoneService, never()).findByProjectId("p1"); // root's own milestones aren't fetched once it has sub-projects
    }

    @Test
    void writeExport_subProjectWithMilestones_getsOneRowPerMilestone_repeatingRootAndSubColumns() throws Exception {
        ProjectEntity root = rootProject("p1", "Project D", "PRJ-4", "100000.00", "USD");
        ProjectEntity sub = subProject("s1", "Sub One", "PRJ-4-1", "40000.00", root);
        MilestoneEntity m1 = milestone("m1", "Sub Milestone", "PRJ-4-1-1", "20000.00", LocalDate.of(2026, 6, 30));
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(sub));
        when(milestoneService.findByProjectId("s1")).thenReturn(List.of(m1));

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("Project D", "PRJ-4", "100000.00", "USD",
                "Sub One", "PRJ-4-1", "40000.00", "Sub Milestone", "PRJ-4-1-1", "20000.00", "2026-06-30");
    }

    @Test
    void writeExport_multipleSubProjects_eachGetsItsOwnRowsIndependently() throws Exception {
        ProjectEntity root = rootProject("p1", "Project E", "PRJ-5", "100000.00", "USD");
        ProjectEntity subOne = subProject("s1", "Sub One", "PRJ-5-1", "40000.00", root);
        ProjectEntity subTwo = subProject("s2", "Sub Two", "PRJ-5-2", "60000.00", root);
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of(subOne, subTwo));
        when(milestoneService.findByProjectId("s1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("s2")).thenReturn(List.of());

        List<String[]> rows = exportAndParse(null);

        assertThat(rows).hasSize(3); // header + one row per childless sub-project
        assertThat(rows.get(1)[4]).isEqualTo("Sub One");
        assertThat(rows.get(2)[4]).isEqualTo("Sub Two");
    }

    @Test
    void writeExport_amountsWrittenAsPlainDecimalString_notScientificNotation() throws Exception {
        // BigDecimal.toPlainString(), not BigDecimals.normalise()'s stripTrailingZeros() — a round
        // number like 100000.00 must stay "100000.00" in the CSV, not collapse to "1E+5".
        ProjectEntity root = rootProject("p1", "Project F", "PRJ-6", "100000.00", "USD");
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of());

        List<String[]> rows = exportAndParse(null);

        assertThat(rows.get(1)[2]).isEqualTo("100000.00");
    }

    // -------------------------------------------------------------------------
    // proIds filter
    // -------------------------------------------------------------------------

    @Test
    void writeExport_withoutProIds_queriesAllRootsForOrg() throws Exception {
        when(projectRepository.findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any())).thenReturn(new PageImpl<>(List.of()));

        exportAndParse(List.of());

        verify(projectRepository).findByOrganisationIdAndParentProjectIsNull(eq(ORG_ID), any());
        verify(projectRepository, never()).findByOrganisationIdAndProIdInAndParentProjectIsNull(any(), anyList(), any());
    }

    @Test
    void writeExport_withProIds_filtersToJustThoseRoots() throws Exception {
        ProjectEntity root = rootProject("p1", "Project G", "PRJ-7", "100000.00", "USD");
        when(projectRepository.findByOrganisationIdAndProIdInAndParentProjectIsNull(eq(ORG_ID), eq(List.of("PRJ-7")), any()))
                .thenReturn(new PageImpl<>(List.of(root)));
        when(projectRepository.findByParentProjectId("p1")).thenReturn(List.of());
        when(milestoneService.findByProjectId("p1")).thenReturn(List.of());

        List<String[]> rows = exportAndParse(List.of("PRJ-7"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)[1]).isEqualTo("PRJ-7");
        verify(projectRepository, never()).findByOrganisationIdAndParentProjectIsNull(any(), any());
    }

    // --- helpers ---

    private List<String[]> exportAndParse(List<String> proIds) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeProjectsMilestonesExport(ORG_ID, proIds, PAGEABLE, out);
        try (CSVReader reader = new CSVReader(new java.io.StringReader(out.toString()))) {
            return reader.readAll();
        }
    }

    private ProjectEntity rootProject(String id, String title, String proId, String totalAmount, String currency) {
        return ProjectEntity.builder()
                .id(id)
                .organisationId(ORG_ID)
                .projectTitle(title)
                .proId(proId)
                .totalAmount(new BigDecimal(totalAmount))
                .currency(currency)
                .build();
    }

    private ProjectEntity subProject(String id, String title, String proId, String totalAmount, ProjectEntity parent) {
        return ProjectEntity.builder()
                .id(id)
                .organisationId(ORG_ID)
                .projectTitle(title)
                .proId(proId)
                .totalAmount(new BigDecimal(totalAmount))
                .currency(parent.getCurrency())
                .parentProject(parent)
                .build();
    }

    private MilestoneEntity milestone(String id, String title, String proId, String amount, LocalDate date) {
        return MilestoneEntity.builder()
                .id(id)
                .milestoneTitle(title)
                .proId(proId)
                .milestoneAmount(new BigDecimal(amount))
                .milestoneDate(date)
                .build();
    }

}
