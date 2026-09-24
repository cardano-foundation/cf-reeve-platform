package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.cardanofoundation.lob.app.funding.domain.csv.EventCsvLine;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvTypeDetector;
import org.cardanofoundation.lob.app.funding.domain.csv.ProjectMilestoneCsvLine;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventProjectAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.EventMilestoneAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.EventProjectAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingFileImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowError;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@ExtendWith(MockitoExtension.class)
class FundingBulkImportServiceTest {

    private static final String ORG_ID = "org1";

    @Mock
    private CsvParser<ProjectMilestoneCsvLine> projectMilestoneCsvParser;
    @Mock
    private CsvParser<EventCsvLine> eventCsvParser;
    @Mock
    private FundingCsvTypeDetector csvTypeDetector;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectTreeUpdateService projectTreeUpdateService;
    @Mock
    private ProjectStructureService projectStructureService;
    @Mock
    private MilestoneService milestoneService;
    @Mock
    private SpendingEventService spendingEventService;
    @Mock
    private OrganisationPublicApiIF organisationPublicApi;

    private FundingBulkImportService bulkImportService;

    @BeforeEach
    void setUp() {
        // Real (unmocked) transaction runner: outside a Spring container there is no active
        // transaction to roll back, so it just runs the work — sufficient to exercise the shared
        // processing path (including which rows get reported/counted); the actual DB rollback
        // behavior is integration-test territory.
        bulkImportService = new FundingBulkImportService(projectMilestoneCsvParser, eventCsvParser,
                csvTypeDetector, projectRepository, projectService, projectTreeUpdateService, projectStructureService,
                milestoneService, spendingEventService, organisationPublicApi, new FundingBulkImportTransactionRunner());
        lenient().when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));
    }

    private static MultipartFile file(String name) {
        MultipartFile file = mock(MultipartFile.class);
        lenient().when(file.getOriginalFilename()).thenReturn(name);
        return file;
    }

    private static ProjectMilestoneCsvLine rootLine(String title, String totalAmount, String currency) {
        ProjectMilestoneCsvLine line = new ProjectMilestoneCsvLine();
        line.setProjectTitle(title);
        line.setTotalAmount(totalAmount);
        line.setCurrency(currency);
        return line;
    }

    private static ProjectMilestoneCsvLine continuationLine(String rootTitle) {
        ProjectMilestoneCsvLine line = new ProjectMilestoneCsvLine();
        line.setProjectTitle(rootTitle);
        return line;
    }

    private static ProjectEntity projectEntity(String id, String title, String currency) {
        return ProjectEntity.builder().id(id).organisationId(ORG_ID).projectTitle(title).currency(currency).build();
    }

    private static ProjectEntity subProjectEntity(String id, String title, String currency, ProjectEntity parent) {
        return ProjectEntity.builder().id(id).organisationId(ORG_ID).projectTitle(title).currency(currency).parentProject(parent).build();
    }

    private static ProjectView successProjectView(String projectId) {
        return ProjectView.builder().projectId(projectId).build();
    }

    private static ProblemDetail problem(HttpStatus status, String title) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, "detail");
        p.setTitle(title);
        return p;
    }

    // -------------------------------------------------------------------------
    // Request-level validation
    // -------------------------------------------------------------------------

    @Test
    void organisationNotFound_returnsRequestLevelError() {
        when(organisationPublicApi.findByOrganisationId("missing")).thenReturn(Optional.empty());
        BulkImportRequest request = BulkImportRequest.builder().organisationId("missing")
                .files(List.of(file("x.csv"))).build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getError()).isPresent();
        assertThat(result.getError().get().getTitle()).isEqualTo(ErrorTitleConstants.ORGANISATION_NOT_FOUND);
    }

    @Test
    void noFilesUploaded_returnsRequestLevelError() {
        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of()).build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getError()).isPresent();
        assertThat(result.getError().get().getTitle()).isEqualTo(ErrorTitleConstants.NO_FILES_UPLOADED);
    }

    @Test
    void unrecognizedFileType_reportsFileLevelError() {
        MultipartFile file = file("mystery.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.empty());
        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();

        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles()).hasSize(1);
        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isNull();
        assertThat(fileResult.getRowErrors()).hasSize(1);
    }

    @Test
    void fileMissingAColumnFromItsTemplate_reportsFileLevelErrorNamingIt_insteadOfParsing() {
        // A column entirely absent from the header row (not merely blank on the data rows) — e.g.
        // "Amount FCY" trimmed out of an Events file — must be rejected up front with a clear message,
        // rather than silently parsing every row's amountFcy as null and only surfacing confusion much
        // later via an unrelated business-rule error.
        MultipartFile file = file("funding-event.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(csvTypeDetector.missingHeaders(file, FundingCsvFileType.EVENTS)).thenReturn(Set.of("Amount FCY"));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles()).hasSize(1);
        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isEqualTo(FundingCsvFileType.EVENTS);
        assertThat(fileResult.getRowsSucceeded()).isZero();
        assertThat(fileResult.getRowErrors()).hasSize(1);
        assertThat(fileResult.getRowErrors().get(0).getReason()).contains("Amount FCY");
        verifyNoInteractions(eventCsvParser);
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — root project create vs. update
    // -------------------------------------------------------------------------

    @Test
    void createsNewRootProject_whenItDoesNotExistYet() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine rootRow = rootLine("Project A", "100000.00", "USD");
        rootRow.setProjectId("Project A");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isEqualTo(1);
        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();

        ArgumentCaptor<ProjectWithMilestonesCreateRequest> captor = ArgumentCaptor.forClass(ProjectWithMilestonesCreateRequest.class);
        verify(projectService).createWithMilestones(captor.capture());
        assertThat(captor.getValue().getProjectTitle()).isEqualTo("Project A");
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("100000.00");
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void updatesExistingRootProject() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootLine("Project A", "120000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(projectService, never()).createWithMilestones(any());

        ArgumentCaptor<ProjectWithMilestonesCreateRequest> captor = ArgumentCaptor.forClass(ProjectWithMilestonesCreateRequest.class);
        verify(projectTreeUpdateService).applyRootFields(any(), captor.capture());
        // projectTitle is unchanged (same value re-sent) and is never forwarded on update.
        assertThat(captor.getValue().getProjectTitle()).isNull();
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("120000.00");
    }

    @Test
    void blankProjectTitle_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootLine("", "1000.00", "USD"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectRepository, never()).findByOrganisationIdAndProjectTitleAndParentProjectIsNull(any(), any());
    }

    @ParameterizedTest(name = "root create fails when totalAmount=\"{0}\" currency=\"{1}\"")
    @CsvSource({
            "not-a-number, USD",
            "100000.00,    ''",
            "'',           USD"
    })
    void createInvalidOrMissingAmountOrCurrency_reportsError(String totalAmount, String currency) {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootLine("Project A", totalAmount, currency))));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void rootCreateMissingProjectId_reportsError() {
        // Project ID is mandatory to create a root project (matching the UI's own required-field
        // treatment of this input) — no more defaulting to Project Title when the column is blank.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootLine("Project A", "100000.00", "USD")))); // Project ID deliberately left unset
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("Project ID");
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void rootCreateBusinessError_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine rootRow = rootLine("Project A", "100000.00", "USD");
        rootRow.setProjectId("Project A");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any()))
                .thenReturn(ProjectView.error(problem(HttpStatus.CONFLICT, "PROJECT_FUNDING_ID_ALREADY_USED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectRepository, never()).findById("p1");
    }

    @Test
    void rootUpdateBusinessError_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootLine("Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));
        when(projectTreeUpdateService.isLockedByPublishedEvent(any())).thenReturn(true);

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getTitle()).isEqualTo(ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED);
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — row-order independence (the whole point of the
    // same-row design): a sub-project or root-data row may appear in any order
    // within its group and still resolve correctly.
    // -------------------------------------------------------------------------

    @Test
    void rootDataOnLaterRow_stillCreatesRootCorrectly() {
        // File order: a sub-project row first (blank root columns), then the row that actually
        // carries the root's Total Amount/Currency. The root must still be found and created using
        // that later row's data — not fail because idxs.get(0) lacked it.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine subRowFirst = continuationLine("Project A");
        subRowFirst.setSubProjectTitle("Sub One");
        subRowFirst.setSubProjectId("sub-1");
        subRowFirst.setSubTotalAmount("40000.00");

        ProjectMilestoneCsvLine rootRowSecond = rootLine("Project A", "100000.00", "USD");
        rootRowSecond.setProjectId("Project A");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(subRowFirst, rootRowSecond)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // Sub Project ID is set on this row, so the existence check now goes through
        // findByParentProjectIdAndProId (unstubbed here, defaulting to empty — "not found", same as
        // the title-based lookup this superseded).
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any()))
                .thenReturn(Either.right(subProjectEntity("s1", "Sub One", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getProjectsCreated()).isEqualTo(2); // root + sub

        ArgumentCaptor<ProjectWithMilestonesCreateRequest> captor = ArgumentCaptor.forClass(ProjectWithMilestonesCreateRequest.class);
        verify(projectService).createWithMilestones(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("100000.00");
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — sub-project on the same row as its root
    // -------------------------------------------------------------------------

    @Test
    void createsNewSubProject_linkedToTheRowsRoot() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubProjectId("sub-1");
        line.setSubTotalAmount("40000.00");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // existence check now goes through findByParentProjectIdAndProId (unstubbed -> empty, "not found")
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any()))
                .thenReturn(Either.right(subProjectEntity("s1", "Sub One", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isEqualTo(2); // root + sub
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(2); // root row + sub row
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(projectStructureService).createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any());
    }

    @Test
    void createsNewSubProject_withBlankSubProjectId_autoAssignsInsteadOfRejecting() {
        // Sub Project ID is no longer mandatory on a CSV creation row (see LOB-2384's CSV-optional-ID
        // follow-up, which dropped SUBPROJECT_PROID_REQUIRED entirely) — a blank value must auto-assign
        // via ProjectStructureService (explicitProId null), exactly the same as the JSON API, not be
        // rejected as a row error.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("40000.00");
        // Sub Project ID deliberately left unset.

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // No Sub Project ID on the row -> matching falls back to title (findByParentProjectIdAndProjectTitle,
        // unstubbed -> empty, "not found"), same as before this feature.
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), isNull(), any(), any(), any()))
                .thenReturn(Either.right(subProjectEntity("s1", "Sub One", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getProjectsCreated()).isEqualTo(2); // root + sub
        verify(projectStructureService).createSubProject(eq(root), eq("Sub One"), isNull(), any(), any(), any());
    }

    @Test
    void updatesExistingSubProject() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("50000.00");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.of(sub));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isEqualTo(2); // root + sub
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ProjectTreeNodeRequest> captor = ArgumentCaptor.forClass(ProjectTreeNodeRequest.class);
        verify(projectTreeUpdateService).applySubProjectFields(eq(sub), captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    void subProjectMissingTotalAmountOnCreate_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // The whole group — including the root, which did succeed on its own — is reported as rolled
        // back along with the sub-project's failure: a group is all-or-nothing, so its create/update
        // counts must not claim anything persisted when part of the group failed.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        String reason = result.getFiles().get(0).getRowErrors().get(0).getReason();
        // The reported message must make the rollback itself visible, naming the project it applies to.
        assertThat(reason)
                .contains("Sub Total Amount")
                .contains("rolled back")
                .contains("Project A");
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
    }

    @Test
    void groupFailure_rollbackNoteSurvivesUnderDryRunToo() {
        // processGroupAtomically's error-annotation and count-zeroing don't branch on dryRun — they key
        // purely on whether the group produced any row error — so the same message/count shape must
        // appear whether this is a real import or a preview.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).dryRun(true).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason())
                .contains("rolled back").contains("Project A");
    }

    @Test
    void subTotalAmountWithNoSubProjectTitle_reportsErrorInsteadOfAttachingToRoot() {
        // Reproduces uploading a CSV whose "Sub Project Title" column is missing entirely: opencsv
        // (an optional column) leaves subProjectTitle null on every row with no parsing error, so
        // without this guard a Sub Total Amount left on the row would be silently dropped and any
        // milestone on the same row would be misattached to the root instead of the intended
        // sub-project. The row must fail instead of doing either.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubTotalAmount("40000.00"); // Sub Project Title left blank/absent
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // The group (just the root here) is rolled back along with the row's error — a group is
        // all-or-nothing.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("Sub Project Title");
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void subProjectMissingCurrencyOnCreate_inheritsRootCurrency() {
        // There is no "Sub Currency" column: ProjectStructureService.createSubProject always
        // receives null for a sub-project's currency and defaults it to the parent's currency.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubProjectId("sub-1");
        line.setSubTotalAmount("40000.00"); // no sub currency supplied
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // existence check now goes through findByParentProjectIdAndProId (unstubbed -> empty, "not found")
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), isNull()))
                .thenReturn(Either.right(subProjectEntity("s1", "Sub One", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getProjectsCreated()).isEqualTo(2); // root + sub
        verify(projectStructureService).createSubProject(eq(root), eq("Sub One"), any(), any(), any(), isNull());
    }

    @Test
    void subProjectCreateFails_stillProcessesEveryRowButRollsBackTheWholeGroup() {
        // Every row in the group is still attempted (Sub Good's own create call still fires below), so
        // every row's own error is reported individually — but the group as a whole is all-or-nothing:
        // Sub Bad's failure rolls back the root and Sub Good along with it.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine badSubRow = rootLine("Project A", "100000.00", "USD");
        badSubRow.setProjectId("Project A");
        badSubRow.setSubProjectTitle("Sub Bad");
        badSubRow.setSubProjectId("sub-bad");
        badSubRow.setSubTotalAmount("40000.00");

        ProjectMilestoneCsvLine goodSubRow = continuationLine("Project A");
        goodSubRow.setSubProjectTitle("Sub Good");
        goodSubRow.setSubProjectId("sub-good");
        goodSubRow.setSubTotalAmount("30000.00");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(badSubRow, goodSubRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // existence check now goes through findByParentProjectIdAndProId (unstubbed -> empty, "not found")
        when(projectStructureService.createSubProject(eq(root), eq("Sub Bad"), any(), any(), any(), any()))
                .thenReturn(Either.left(problem(HttpStatus.CONFLICT, "PROJECT_TITLE_ALREADY_EXISTS")));
        // existence check now goes through findByParentProjectIdAndProId (unstubbed -> empty, "not found")
        when(projectStructureService.createSubProject(eq(root), eq("Sub Good"), any(), any(), any(), any()))
                .thenReturn(Either.right(subProjectEntity("s-good", "Sub Good", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Sub Good's create call still happened (verified below) — it's rolled back along with
        // everything else in the group, so the reported counts must show nothing persisted.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getRowNumber()).isEqualTo(1);
        verify(projectStructureService).createSubProject(eq(root), eq("Sub Good"), any(), any(), any(), any());
    }

    @Test
    void subProjectUpdateFails_rollsBackTheRootUpdateFromTheSameGroup() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("40000.00");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.of(sub));
        when(projectTreeUpdateService.isLockedByPublishedEvent(sub)).thenReturn(true);

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // The root's own update did happen (its applyRootFields call succeeded by default), but it's
        // rolled back along with the sub-project's failure — a group is all-or-nothing.
        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void groupValidatesWholeTreeCoverageOnce_afterEveryRowIsApplied_forRootPlusEveryTouchedSubProject() {
        // LOB-2365 follow-up: ProjectService#updateProject's per-row coverage check was removed from
        // this path specifically because it can't see a group's other, not-yet-processed rows — this
        // group-level, once-at-the-end check (ProjectTreeUpdateService#validateWholeTreeCoverage) is
        // what replaces it. Verifies the wiring: called once, with root + every sub-project actually
        // touched in the group, and its rejection becomes a row error that fails the group.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("40000.00");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.of(sub));
        when(projectTreeUpdateService.validateWholeTreeCoverage(Set.of("p1", "s1")))
                .thenReturn(Optional.of(problem(HttpStatus.BAD_REQUEST, "PROJECT_AMOUNT_BELOW_SUBPROJECTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getTitle()).isEqualTo("PROJECT_AMOUNT_BELOW_SUBPROJECTS");
        verify(projectTreeUpdateService, times(1)).validateWholeTreeCoverage(any());
    }

    @Test
    void groupSkipsWholeTreeCoverageCheck_whenAnEarlierRowAlreadyFailed() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("not-a-number");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectTreeUpdateService, never()).validateWholeTreeCoverage(any());
    }

    @Test
    void oneGroupFails_anIndependentGroupInTheSameFileIsUnaffectedAndItsRollbackNoteNamesTheRightProject() {
        // Two unrelated root projects in one file: "Project Good" (a single, valid root-only row) and
        // "Project Bad" (a root plus a sub-project row that fails). Atomicity is per group, not per
        // file, so Project Good's create must survive, and the rollback note on Project Bad's row error
        // must name Project Bad — not accidentally leak Project Good's title (or vice versa).
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine goodRoot = rootLine("Project Good", "50000.00", "USD");
        goodRoot.setProjectId("Project Good");
        ProjectMilestoneCsvLine badRoot = rootLine("Project Bad", "100000.00", "USD");
        badRoot.setProjectId("Project Bad");
        badRoot.setSubProjectTitle("Sub One"); // Sub Total Amount left blank -> fails to create

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(goodRoot, badRoot)));

        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenAnswer(invocation -> {
            ProjectWithMilestonesCreateRequest req = invocation.getArgument(0);
            return successProjectView(req.getProjectTitle().equals("Project Good") ? "pGood" : "pBad");
        });
        when(projectRepository.findById("pGood")).thenReturn(Optional.of(projectEntity("pGood", "Project Good", "USD")));
        when(projectRepository.findById("pBad")).thenReturn(Optional.of(projectEntity("pBad", "Project Bad", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Only Project Bad's group is discarded — Project Good's create is untouched.
        assertThat(result.getProjectsCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        String reason = result.getFiles().get(0).getRowErrors().get(0).getReason();
        assertThat(reason).contains("Project Bad").doesNotContain("Project Good");
    }

    @Test
    void multipleFailingRowsInTheSameGroup_eachErrorIsAnnotatedWithThatGroupsProject() {
        // Two independently-bad rows within the very same "Project A" group: a sub-project row with a
        // missing amount, and a milestone row with an orphaned amount (no title). Both errors must carry
        // the same group's rollback note.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine badSubRow = rootLine("Project A", "100000.00", "USD");
        badSubRow.setProjectId("Project A");
        badSubRow.setSubProjectTitle("Sub One"); // Sub Total Amount left blank -> fails

        ProjectMilestoneCsvLine badMilestoneRow = continuationLine("Project A");
        badMilestoneRow.setMilestoneAmount("20000.00"); // Milestone Title left blank -> orphaned data

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(badSubRow, badMilestoneRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(2);
        assertThat(result.getFiles().get(0).getRowErrors())
                .allSatisfy(error -> assertThat(error.getReason()).contains("rolled back").contains("Project A"));
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — milestones on the same row (root-level or sub-level)
    // -------------------------------------------------------------------------

    @Test
    void milestoneOnRootRow_whenNoSubProject_attachesToRoot() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project B", "20000.00", "USD");
        line.setProjectId("Project B");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneId("ms-1");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project B", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.createMilestone(eq("p1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService).createMilestone(eq("p1"), any(), any());
    }

    @Test
    void createsNewMilestone_withBlankMilestoneId_autoAssignsInsteadOfRejecting() {
        // Milestone ID is no longer mandatory on a CSV creation row (see LOB-2384's CSV-optional-ID
        // follow-up, which dropped MILESTONE_PROID_REQUIRED entirely) — a blank value must auto-assign
        // via MilestoneService (explicitProId null), exactly the same as the JSON API, not be rejected
        // as a row error.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project B", "20000.00", "USD");
        line.setProjectId("Project B");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");
        // Milestone ID deliberately left unset.

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project B", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.createMilestone(eq("p1"), any(), isNull())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService).createMilestone(eq("p1"), any(), isNull());
    }

    @Test
    void milestoneOnSubProjectRow_attachesToTheSubProjectNotTheRoot() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubProjectId("sub-1");
        line.setSubTotalAmount("40000.00");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneId("ms-1");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // Sub Project ID / Milestone ID are set on this row, so both existence checks now go through
        // the ID-based lookups (unstubbed -> empty, "not found", same as the title-based ones these superseded).
        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any())).thenReturn(Either.right(sub));
        when(milestoneService.createMilestone(eq("s1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService).createMilestone(eq("s1"), any(), any());
        verify(milestoneService, never()).createMilestone(eq("p1"), any(), any());
    }

    @Test
    void milestoneCreate_derivesCurrencyFromProject_notFromAColumn() {
        // There is no "Milestone Currency" CSV column — a milestone's currency always matches its
        // (already-resolved) project's currency.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneId("ms-1");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "EUR");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        when(milestoneService.createMilestone(eq("p1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        bulkImportService.importFiles(request);

        ArgumentCaptor<MilestoneCreateRequest> captor = ArgumentCaptor.forClass(MilestoneCreateRequest.class);
        verify(milestoneService).createMilestone(eq("p1"), captor.capture(), any());
        assertThat(captor.getValue().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void milestoneUpdate_derivesCurrencyFromProject_titleNeverSent() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("25000.00"); // only the amount changed

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        MilestoneEntity existing = MilestoneEntity.builder().id("m1").milestoneTitle("Milestone One").build();
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.of(existing));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesUpdated()).isEqualTo(1);
        ArgumentCaptor<MilestoneCreateRequest> captor = ArgumentCaptor.forClass(MilestoneCreateRequest.class);
        verify(projectTreeUpdateService).applyExistingMilestone(eq(root), eq(existing), captor.capture(), any());
        assertThat(captor.getValue().getMilestoneTitle()).isNull();
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
        assertThat(captor.getValue().getMilestoneAmount()).isEqualByComparingTo("25000.00");
    }

    @Test
    void projectHasNoCurrency_reportsErrorOnMilestoneCreate() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity rootWithoutCurrency = projectEntity("p1", "Project A", null);
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(rootWithoutCurrency));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("currency");
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @ParameterizedTest(name = "milestone create fails when amount=\"{0}\" date=\"{1}\"")
    @CsvSource({
            "'',       2026-06-30",
            "20000.00, ''"
    })
    void milestoneCreateMissingRequiredField_reportsError(String amount, String date) {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount(amount);
        line.setMilestoneDate(date);

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestoneAmountWithNoMilestoneTitle_reportsErrorInsteadOfSilentlyDropping() {
        // Reproduces uploading a CSV whose "Milestone Title" column is missing entirely: opencsv
        // (an optional column) leaves milestoneTitle null on every row with no parsing error, so
        // without this guard the Milestone Amount/Date left on the row would just be silently
        // dropped — no milestone created, no error reported. The sub-project's own create call still
        // fires (verified below), but the group as a whole rolls back along with the orphaned
        // milestone data's error.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = rootLine("Project A", "100000.00", "USD");
        line.setProjectId("Project A");
        line.setSubProjectTitle("Sub One");
        line.setSubProjectId("sub-1");
        line.setSubTotalAmount("40000.00");
        line.setMilestoneAmount("20000.00"); // Milestone Title left blank/absent
        line.setMilestoneDate("2026-06-30");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        // existence check now goes through findByParentProjectIdAndProId (unstubbed -> empty, "not found")
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), isNull()))
                .thenReturn(Either.right(subProjectEntity("s1", "Sub One", "USD", root)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("Milestone Title");
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestoneInvalidAmount_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("not-a-number");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestoneInvalidDate_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("not-a-date");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestoneUpdateFails_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("25000.00");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(root));
        MilestoneEntity existing = MilestoneEntity.builder().id("m1").milestoneTitle("Milestone One").build();
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.of(existing));
        when(projectTreeUpdateService.applyExistingMilestone(eq(root), eq(existing), any(), any()))
                .thenReturn(Optional.of(problem(HttpStatus.CONFLICT, "SPENDING_EVENT_ALREADY_PUBLISHED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void milestoneCreateFails_reportsError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine line = continuationLine("Project A");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneId("ms-1");
        line.setMilestoneAmount("20000.00");
        line.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Project A"))
                .thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.createMilestone(eq("p1"), any(), any()))
                .thenReturn(MilestoneView.error(problem(HttpStatus.CONFLICT, "MILESTONE_TITLE_ALREADY_EXISTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void milestoneFailure_stillProcessesTheGoodRowButRollsBackTheWholeGroup() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine badMilestoneRow = rootLine("Project A", "100000.00", "USD");
        badMilestoneRow.setProjectId("Project A");
        badMilestoneRow.setMilestoneTitle("Milestone Bad");
        badMilestoneRow.setMilestoneAmount("not-a-number");

        ProjectMilestoneCsvLine goodMilestoneRow = continuationLine("Project A");
        goodMilestoneRow.setMilestoneTitle("Milestone Good");
        goodMilestoneRow.setMilestoneId("ms-good");
        goodMilestoneRow.setMilestoneAmount("20000.00");
        goodMilestoneRow.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(badMilestoneRow, goodMilestoneRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.createMilestone(eq("p1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Milestone Good's own create call still happened (verified below) — it's rolled back along
        // with the rest of the group because Milestone Bad, earlier in the same group, failed.
        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getRowNumber()).isEqualTo(1);
        verify(milestoneService).createMilestone(eq("p1"), any(), any());
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — same-titled milestones across different sub-projects
    // -------------------------------------------------------------------------

    @Test
    void sameMilestoneTitleAcrossDifferentSubProjects_bothCreatedIndependently() {
        // Milestone titles are unique per project, not across siblings — "Milestone One" under Sub
        // One and under Sub Two are two distinct milestones.
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine subOneRow = rootLine("Project A", "100000.00", "USD");
        subOneRow.setProjectId("Project A");
        subOneRow.setSubProjectTitle("Sub One");
        subOneRow.setSubProjectId("sub-1");
        subOneRow.setSubTotalAmount("40000.00");
        subOneRow.setMilestoneTitle("Milestone One");
        subOneRow.setMilestoneId("s1-ms-1");
        subOneRow.setMilestoneAmount("20000.00");
        subOneRow.setMilestoneDate("2026-06-30");

        ProjectMilestoneCsvLine subTwoRow = continuationLine("Project A");
        subTwoRow.setSubProjectTitle("Sub Two");
        subTwoRow.setSubProjectId("sub-2");
        subTwoRow.setSubTotalAmount("40000.00");
        subTwoRow.setMilestoneTitle("Milestone One"); // same title, different (sub-)project
        subTwoRow.setMilestoneId("s2-ms-1");
        subTwoRow.setMilestoneAmount("20000.00");
        subTwoRow.setMilestoneDate("2026-06-30");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.right(List.of(subOneRow, subTwoRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));

        ProjectEntity subOne = subProjectEntity("s1", "Sub One", "USD", root);
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any())).thenReturn(Either.right(subOne));
        when(milestoneService.createMilestone(eq("s1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        ProjectEntity subTwo = subProjectEntity("s2", "Sub Two", "USD", root);
        when(projectStructureService.createSubProject(eq(root), eq("Sub Two"), any(), any(), any(), any())).thenReturn(Either.right(subTwo));
        when(milestoneService.createMilestone(eq("s2"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m2").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getMilestonesCreated()).isEqualTo(2);
        verify(milestoneService).createMilestone(eq("s1"), any(), any());
        verify(milestoneService).createMilestone(eq("s2"), any(), any());
    }

    @Test
    void twoMilestonesInSameSubProject_bothCreated_subProjectResolvedOnceEach() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));

        ProjectMilestoneCsvLine row1 = rootLine("Project A", "100000.00", "USD");
        row1.setProjectId("Project A");
        row1.setSubProjectTitle("Sub One");
        row1.setSubProjectId("sub-1");
        row1.setSubTotalAmount("40000.00");
        row1.setMilestoneTitle("Milestone One");
        row1.setMilestoneId("ms-1");
        row1.setMilestoneAmount("20000.00");
        row1.setMilestoneDate("2026-06-30");

        ProjectMilestoneCsvLine row2 = continuationLine("Project A");
        row2.setSubProjectTitle("Sub One");
        row2.setMilestoneTitle("Milestone Two");
        row2.setMilestoneId("ms-2");
        row2.setMilestoneAmount("20000.00");
        row2.setMilestoneDate("2026-07-15");

        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));

        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);
        // Row 1 carries Sub Project ID, so it resolves via the ID-based lookup (unstubbed -> empty ->
        // create). Row 2 is a continuation with no ID of its own, so it still resolves "Sub One" by
        // title — by which point the sub-project already exists (created by row 1 in the same group).
        when(projectRepository.findByParentProjectIdAndProjectTitle("p1", "Sub One")).thenReturn(Optional.of(sub));
        when(projectStructureService.createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any())).thenReturn(Either.right(sub));
        when(milestoneService.createMilestone(eq("s1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getMilestonesCreated()).isEqualTo(2);
        verify(projectStructureService, times(1)).createSubProject(eq(root), eq("Sub One"), any(), any(), any(), any());
        verify(projectTreeUpdateService, times(1)).applySubProjectFields(eq(sub), any());
        verify(milestoneService, times(2)).createMilestone(eq("s1"), any(), any());
    }

    // -------------------------------------------------------------------------
    // Events file — title-based references
    // -------------------------------------------------------------------------

    @Test
    void eventsFile_groupsAllocationRowsIntoOneEvent_acrossTwoRootProjects() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "10000.00");
        EventCsvLine row2 = eventLine("SPENDING", "GRANT-1", "USD", "Project B", "Milestone Two", "5000.00");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project B"))
                .thenReturn(List.of(projectEntity("p2", "Project B", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p2", "Milestone Two"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m2").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isEqualTo(1);
        assertThat(result.getAllocationsCreated()).isEqualTo(2);
        verify(spendingEventService, times(1)).createEvent(any());

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        assertThat(captor.getValue().getAllocations()).hasSize(2);
        assertThat(captor.getValue().getAllocations().get(0).getMilestones()).hasSize(1);
        assertThat(captor.getValue().getAllocations()).allSatisfy(a -> assertThat(a.getSubProjects()).isEmpty());
    }

    @Test
    void eventsFile_rowsSharingFundingIdAndHash_butDifferentCategoryVendorOrDate_areSeparateEvents() {
        // Two rows share Funding ID/Hash/Currency (what used to be the whole grouping key), but are
        // otherwise different real-world transactions — different category, vendor and date, exactly
        // like the "Create event" UI form fields that must match for two allocations to belong to the
        // same event. They must import as two distinct events, not one event with two allocations.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "10000.00");
        row1.setCategory("Legal & Compliance");
        row1.setVendor("BioEthic Counsel LLP");
        row1.setEventDate("2026-02-15");

        EventCsvLine row2 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "5000.00");
        row2.setCategory("Infrastructure");
        row2.setVendor("AWS Cloud Health Services");
        row2.setEventDate("2026-05-01");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isEqualTo(2);
        assertThat(result.getAllocationsCreated()).isEqualTo(2);
        verify(spendingEventService, times(2)).createEvent(any());
    }

    @Test
    void eventsFile_spendingRowsSharingFundingIdHashCategoryVendorAndDate_butDifferentAmountOrReceiptHash_areSeparateEvents() {
        // Two SPENDING rows share Funding ID, Funding Hash, Category, Vendor and Event Date, but
        // describe different amounts and a different receipt hash — still two distinct real-world
        // transactions (e.g. an initial payment and a top-up against the same category/vendor on the
        // same day), not two allocations of the same spend. They must import as two separate events.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "10000.00");
        row1.setCategory("Infrastructure");
        row1.setVendor("AWS Cloud Health Services");
        row1.setEventDate("2026-05-01");
        row1.setHash("sha256:receipt-one");
        row1.setAmountFcy("10000.00");
        row1.setCurrencyFcy("USD");
        row1.setAmountRcy("10000.00");

        EventCsvLine row2 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "2500.00");
        row2.setCategory("Infrastructure");
        row2.setVendor("AWS Cloud Health Services");
        row2.setEventDate("2026-05-01");
        row2.setHash("sha256:receipt-two");
        row2.setAmountFcy("2500.00");
        row2.setCurrencyFcy("USD");
        row2.setAmountRcy("2500.00");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isEqualTo(2);
        assertThat(result.getAllocationsCreated()).isEqualTo(2);
        verify(spendingEventService, times(2)).createEvent(any());
    }

    @Test
    void eventsFile_fundingRowsSharingFundingIdHashEntityCurrencyAndDate_areOneEventEvenWithDifferentAmountRcy() {
        // Unlike SPENDING, a FUNDING event has no spend detail: Amount RCY is the grant total, not a
        // disambiguating field, so two FUNDING rows that agree on Funding ID, Funding Hash, Funding
        // Entity, Currency and Event Date are the same event even if Amount RCY differs between the
        // rows — they must merge into one event with both allocations, not split in two.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = eventLine("FUNDING", "GRANT-1", "USD", "Project A", "Milestone One", "6000");
        row1.setFundingEntity("Cardano Foundation");
        row1.setEventDate("2026-05-01");
        row1.setAmountRcy("6000");

        EventCsvLine row2 = eventLine("FUNDING", "GRANT-1", "USD", "Project A", "Milestone One", "4000");
        row2.setFundingEntity("Cardano Foundation");
        row2.setEventDate("2026-05-01");
        row2.setAmountRcy("10000");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isEqualTo(1);
        assertThat(result.getAllocationsCreated()).isEqualTo(2);
        verify(spendingEventService, times(1)).createEvent(any());
    }

    @Test
    void eventsFile_nestsSubProjectAllocationUnderItsRoot() {
        // Regression test: a sub-project reference must be nested under its root via `subProjects` —
        // the underlying event-creation logic only resolves a flat projectTitle as a ROOT project, so
        // passing a sub-project's title there would wrongly report it as "not found".
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");

        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        ProjectEntity sub = subProjectEntity("s1", "Sub One", "USD", root);

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One")).thenReturn(List.of(sub));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(1);

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        List<EventProjectAllocationRequest> allocations = captor.getValue().getAllocations();
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getProjectTitle()).isEqualTo("Project A");
        assertThat(allocations.get(0).getMilestones()).isEmpty();
        assertThat(allocations.get(0).getSubProjects()).hasSize(1);
        assertThat(allocations.get(0).getSubProjects().get(0).getProjectTitle()).isEqualTo("Sub One");
        assertThat(allocations.get(0).getSubProjects().get(0).getMilestones()).hasSize(1);
    }

    @Test
    void eventsFile_mergesTwoSubProjectsUnderTheSameRoot() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        EventCsvLine row2 = eventLine("SPENDING", "GRANT-1", "USD", "Sub Two", "Milestone One", "5000.00");

        ProjectEntity root = projectEntity("p1", "Project A", "USD");
        ProjectEntity sub1 = subProjectEntity("s1", "Sub One", "USD", root);
        ProjectEntity sub2 = subProjectEntity("s2", "Sub Two", "USD", root);

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One")).thenReturn(List.of(sub1));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub Two")).thenReturn(List.of(sub2));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s2", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m2").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        // One root allocation, both sub-projects nested under it — not two separate root allocations.
        List<EventProjectAllocationRequest> allocations = captor.getValue().getAllocations();
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getProjectTitle()).isEqualTo("Project A");
        assertThat(allocations.get(0).getSubProjects()).hasSize(2);
    }

    @Test
    void eventsFile_missingProject_reportsErrorAndNeverCreatesAnything() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Unknown Project", "Milestone One", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Unknown Project")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_missingMilestone_reportsErrorAndNeverCreatesAnything() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Unknown Milestone", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One"))
                .thenReturn(List.of(projectEntity("s1", "Sub One", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Unknown Milestone")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_ambiguousProjectReference_reportsError() {
        // A bare title isn't guaranteed unique org-wide (only within its sibling scope) — more than
        // one match must be reported as ambiguous, not silently resolved to either one.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Ambiguous Title", "Milestone One", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Ambiguous Title")).thenReturn(List.of(
                projectEntity("s1", "Ambiguous Title", "USD"), projectEntity("s2", "Ambiguous Title", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("Ambiguous Title");
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_subProjectTitleDisambiguatesAmongSameTitledSubProjects() {
        // "Sub One" exists under two different roots — a bare Project Title reference would be
        // ambiguous (see eventsFile_ambiguousProjectReference_reportsError), but naming the specific
        // root via Project Title + the sub-project via Sub Project Title resolves it deterministically,
        // without ever touching the org-wide broad search.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Root Y", "Sub One", "Milestone One", "10000.00");

        ProjectEntity rootY = projectEntity("rootY", "Root Y", "USD");
        ProjectEntity subUnderY = subProjectEntity("subY", "Sub One", "USD", rootY);

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Root Y"))
                .thenReturn(Optional.of(rootY));
        when(projectRepository.findByParentProjectIdAndProjectTitle("rootY", "Sub One")).thenReturn(Optional.of(subUnderY));
        when(projectRepository.findById("rootY")).thenReturn(Optional.of(rootY));
        when(milestoneService.findByProjectIdAndMilestoneTitle("subY", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(1);
        verify(projectRepository, never()).findByOrganisationIdAndProjectTitle(any(), any());

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        List<EventProjectAllocationRequest> allocations = captor.getValue().getAllocations();
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getProjectTitle()).isEqualTo("Root Y");
        assertThat(allocations.get(0).getSubProjects()).hasSize(1);
        assertThat(allocations.get(0).getSubProjects().get(0).getProjectTitle()).isEqualTo("Sub One");
    }

    @Test
    void eventsFile_subProjectTitleSet_rootNotFound_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "No Such Root", "Sub One", "Milestone One", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "No Such Root"))
                .thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("No Such Root");
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_subProjectTitleSet_subNotFoundUnderThatRoot_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Root Y", "No Such Sub", "Milestone One", "10000.00");
        ProjectEntity rootY = projectEntity("rootY", "Root Y", "USD");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(ORG_ID, "Root Y"))
                .thenReturn(Optional.of(rootY));
        when(projectRepository.findByParentProjectIdAndProjectTitle("rootY", "No Such Sub")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason())
                .contains("No Such Sub").contains("Root Y");
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_blankProjectTitleInAllocationRow_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "", "Milestone One", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_blankMilestoneTitleInAllocationRow_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One"))
                .thenReturn(List.of(projectEntity("s1", "Sub One", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_csvParseFailure_reportsFileLevelError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class))
                .thenReturn(Either.left(problem(HttpStatus.BAD_REQUEST, "CSV_PARSING_ERROR")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isEqualTo(FundingCsvFileType.EVENTS);
        assertThat(fileResult.getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_businessValidationError_isReportedAsRowError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One"))
                .thenReturn(List.of(projectEntity("s1", "Sub One", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any()))
                .thenReturn(SpendingEventView.error(problem(HttpStatus.BAD_REQUEST, "SPEND_NOT_FULLY_ALLOCATED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @ParameterizedTest(name = "event header validation fails: eventType=\"{0}\" fundingId=\"{1}\" currencyRcy=\"{2}\"")
    @CsvSource({
            "'',       GRANT-1, USD",
            "SPENDING, '',      USD",
            "SPENDING, GRANT-1, ''"
    })
    void eventsFile_missingEventTypeFundingIdOrCurrency_reportsError(String eventType, String fundingId, String currencyRcy) {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine(eventType, fundingId, currencyRcy, "Sub One", "Milestone One", "10000.00"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidEventType_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("NOT_A_TYPE", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidEventDate_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        row.setEventDate("not-a-date");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidAmountFcy_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        row.setAmountFcy("not-a-number");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidFxRate_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        row.setFxRate("not-a-number");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidAmountRcy_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "10000.00");
        row.setAmountRcy("not-a-number");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_blankAllocatedAmount_reportsError() {
        // Distinct from "invalid number": the cell is empty, not unparsable.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", ""))));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One"))
                .thenReturn(List.of(projectEntity("s1", "Sub One", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidAllocatedAmount_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("SPENDING", "GRANT-1", "USD", "Sub One", "Milestone One", "not-a-number"))));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Sub One"))
                .thenReturn(List.of(projectEntity("s1", "Sub One", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("s1", "Milestone One"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_twoMilestoneRowsOnSameProjectBothExceedLimit_reportsBothAsOverspendWarningsAndSucceeds() {
        // The hard cap against a milestone's budget was removed for SPENDING — a row exceeding it now
        // imports successfully and is flagged as an overspend warning (traced back to its own CSV row),
        // instead of failing the row (this replaces a now-obsolete regression test for the old error
        // path). FUNDING is different — see eventsFile_fundingRowExceedingMilestoneBudget_isDroppedAsRowError.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row1 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone One", "25000");
        EventCsvLine row2 = eventLine("SPENDING", "GRANT-1", "USD", "Project A", "Milestone Two", "50000");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.of(
                MilestoneEntity.builder().id("m1").milestoneTitle("Milestone One")
                        .milestoneAmount(new java.math.BigDecimal("20000")).build()));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone Two")).thenReturn(Optional.of(
                MilestoneEntity.builder().id("m2").milestoneTitle("Milestone Two")
                        .milestoneAmount(new java.math.BigDecimal("20000")).build()));

        SpendingEventView overspendView = SpendingEventView.builder()
                .eventId("e1")
                .overspend(true)
                .projectAllocations(List.of(EventProjectAllocationView.builder()
                        .projectId("p1")
                        .overspend(false)
                        .milestoneAllocations(List.of(
                                EventMilestoneAllocationView.builder()
                                        .milestoneId("m1").milestoneTitle("Milestone One")
                                        .milestoneAmount(new java.math.BigDecimal("20000"))
                                        .spentAmount(new java.math.BigDecimal("25000"))
                                        .overspend(true)
                                        .build(),
                                EventMilestoneAllocationView.builder()
                                        .milestoneId("m2").milestoneTitle("Milestone Two")
                                        .milestoneAmount(new java.math.BigDecimal("20000"))
                                        .spentAmount(new java.math.BigDecimal("50000"))
                                        .overspend(true)
                                        .build()))
                        .build()))
                .build();
        when(spendingEventService.createEvent(any())).thenReturn(overspendView);

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowWarnings()).hasSize(2);
        assertThat(result.getFiles().get(0).getRowWarnings().get(0).getRowNumber()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowWarnings().get(0).getReason()).contains("Milestone One");
        assertThat(result.getFiles().get(0).getRowWarnings().get(1).getRowNumber()).isEqualTo(2);
        assertThat(result.getFiles().get(0).getRowWarnings().get(1).getReason()).contains("Milestone Two");
        verify(spendingEventService, times(1)).createEvent(any());
    }

    @Test
    void eventsFile_fundingEventRejectedAsOverfunded_isDroppedAsRowError() {
        // Unlike SPENDING (overspend allowed, only flagged), a FUNDING event that would push
        // cumulative funding past a milestone's budget is rejected outright by SpendingEventService
        // (see SpendingEventServiceTest); the whole CSV group is dropped like any other event-level
        // failure — not persisted, not a warning — via the same generic error path exercised by
        // eventsFile_businessValidationError_isReportedAsRowError above.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("FUNDING", "GRANT-1", "USD", "Project A", "Milestone One", "25000");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Project A"))
                .thenReturn(List.of(projectEntity("p1", "Project A", "USD")));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone One")).thenReturn(Optional.of(
                MilestoneEntity.builder().id("m1").milestoneTitle("Milestone One")
                        .milestoneAmount(new java.math.BigDecimal("20000")).build()));
        when(spendingEventService.createEvent(any()))
                .thenReturn(SpendingEventView.error(problem(HttpStatus.BAD_REQUEST, ErrorTitleConstants.MILESTONE_OVERFUNDED)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getTitle()).isEqualTo(ErrorTitleConstants.MILESTONE_OVERFUNDED);
    }

    @Test
    void eventsFile_twoUnknownProjectsInSameEventGroup_reportsBothRowErrors() {
        // Regression test: resolveAllocations used to stop at the first unresolved project in a group,
        // silently dropping every other unresolved project from the same event group's report.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row1 = eventLine("FUNDING", "GRANT-1", "USD", "Unknown One", "Milestone One", "1000");
        EventCsvLine row2 = eventLine("FUNDING", "GRANT-1", "USD", "Unknown Two", "Milestone One", "1000");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Unknown One")).thenReturn(List.of());
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Unknown Two")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(2);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getRowNumber()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("Unknown One");
        assertThat(result.getFiles().get(0).getRowErrors().get(1).getRowNumber()).isEqualTo(2);
        assertThat(result.getFiles().get(0).getRowErrors().get(1).getReason()).contains("Unknown Two");
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_milestoneAllocatedToRootThatHasSubProjects_reportsSubProjectTitleRequired() {
        // "Milestone not found" is technically true here, but misleading: the milestone lives under one
        // of the root's sub-projects, not the root itself, so the row is missing Sub Project Title, not
        // referencing a bad milestone name.
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("FUNDING", "GRANT-1", "USD", "Vaccines", "Milestone 1", "15000");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        ProjectEntity root = projectEntity("p1", "Vaccines", "USD");
        when(projectRepository.findByOrganisationIdAndProjectTitle(ORG_ID, "Vaccines")).thenReturn(List.of(root));
        when(milestoneService.findByProjectIdAndMilestoneTitle("p1", "Milestone 1")).thenReturn(Optional.empty());
        when(projectRepository.existsByParentProjectId("p1")).thenReturn(true);

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        FundingRowError error = result.getFiles().get(0).getRowErrors().get(0);
        assertThat(error.getTitle()).isEqualTo(ErrorTitleConstants.SUBPROJECT_TITLE_REQUIRED);
        assertThat(error.getReason()).contains("Vaccines").contains("Sub Project Title");
        verify(spendingEventService, never()).createEvent(any());
    }

    private static EventCsvLine eventLine(String eventType, String fundingId, String currencyRcy,
            String projectTitle, String milestoneTitle, String allocatedAmount) {
        return eventLine(eventType, fundingId, currencyRcy, projectTitle, null, milestoneTitle, allocatedAmount);
    }

    private static EventCsvLine eventLine(String eventType, String fundingId, String currencyRcy,
            String projectTitle, String subProjectTitle, String milestoneTitle, String allocatedAmount) {
        EventCsvLine line = new EventCsvLine();
        line.setEventType(eventType);
        line.setFundingId(fundingId);
        line.setCurrencyRcy(currencyRcy);
        line.setProjectTitle(projectTitle);
        line.setSubProjectTitle(subProjectTitle);
        line.setMilestoneTitle(milestoneTitle);
        line.setAllocatedAmount(allocatedAmount);
        return line;
    }

    // -------------------------------------------------------------------------
    // Ordering and dry run
    // -------------------------------------------------------------------------

    @Test
    void filesAreProcessedInProjectsMilestonesThenEventsOrder() {
        MultipartFile eventsFile = file("events.csv");
        MultipartFile projectsFile = file("import.csv");
        when(csvTypeDetector.detect(eventsFile)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(csvTypeDetector.detect(projectsFile)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(projectsFile, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(List.of()));
        when(eventCsvParser.parseCsv(eventsFile, EventCsvLine.class)).thenReturn(Either.right(List.of()));

        // Uploaded in reverse order — the service must still process Projects+Milestones before Events.
        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID)
                .files(List.of(eventsFile, projectsFile)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles()).extracting(FundingFileImportResult::getFileType)
                .containsExactly(FundingCsvFileType.PROJECTS_MILESTONES, FundingCsvFileType.EVENTS);
    }

    @Test
    void dryRun_flowsThroughSameProcessingPath() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        ProjectMilestoneCsvLine rootRow = rootLine("Project A", "100000.00", "USD");
        rootRow.setProjectId("Project A");
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(rootRow)));
        // Project ID is now set on the row, so the existence check goes through
        // findByOrganisationIdAndProIdAndParentProjectIsNull instead (unstubbed -> empty, "not found").
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1"));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "Project A", "USD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).dryRun(true).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Note: true "nothing persisted" behavior depends on the real transaction manager rolling
        // back FundingBulkImportTransactionRunner's transaction — not observable with mocked
        // collaborators, so this is covered by an integration/E2E test instead. Here we confirm the
        // dry-run flag is threaded through and the same translation/counting logic still runs.
        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getProjectsCreated()).isEqualTo(1);
        verify(projectService, times(1)).createWithMilestones(any());
    }

    @Test
    void projectsMilestonesFile_csvParseFailure_reportsFileLevelError() {
        MultipartFile file = file("import.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS_MILESTONES));
        when(projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class))
                .thenReturn(Either.left(problem(HttpStatus.BAD_REQUEST, "CSV_PARSING_ERROR")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isEqualTo(FundingCsvFileType.PROJECTS_MILESTONES);
        assertThat(fileResult.getRowsSucceeded()).isZero();
        assertThat(fileResult.getRowErrors()).hasSize(1);
        verify(projectService, never()).createWithMilestones(any());
    }

}
