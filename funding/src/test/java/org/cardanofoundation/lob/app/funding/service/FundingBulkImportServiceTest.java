package org.cardanofoundation.lob.app.funding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

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
import org.cardanofoundation.lob.app.funding.domain.csv.MilestoneCsvLine;
import org.cardanofoundation.lob.app.funding.domain.csv.ProjectCsvLine;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingFileImportResult;
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
    private CsvParser<ProjectCsvLine> projectCsvParser;
    @Mock
    private CsvParser<MilestoneCsvLine> milestoneCsvParser;
    @Mock
    private CsvParser<EventCsvLine> eventCsvParser;
    @Mock
    private FundingCsvTypeDetector csvTypeDetector;
    @Mock
    private FundingProjectRepository projectRepository;
    @Mock
    private ProjectService projectService;
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
        // processing path for dryRun; the actual rollback behavior is integration-test territory.
        bulkImportService = new FundingBulkImportService(projectCsvParser, milestoneCsvParser, eventCsvParser,
                csvTypeDetector, projectRepository, projectService, projectStructureService, milestoneService,
                spendingEventService, organisationPublicApi, new FundingBulkImportTransactionRunner());
        lenient().when(organisationPublicApi.findByOrganisationId(ORG_ID)).thenReturn(Optional.of(new Organisation()));
    }

    private static MultipartFile file(String name) {
        MultipartFile file = mock(MultipartFile.class);
        lenient().when(file.getOriginalFilename()).thenReturn(name);
        return file;
    }

    private static ProjectCsvLine projectLine(String externalId, String title, String totalAmount, String currency) {
        ProjectCsvLine line = new ProjectCsvLine();
        line.setExternalProjectId(externalId);
        line.setProjectTitle(title);
        line.setTotalAmount(totalAmount);
        line.setCurrency(currency);
        return line;
    }

    private static ProjectEntity projectEntity(String id, String externalId) {
        return ProjectEntity.builder().id(id).organisationId(ORG_ID).externalProjectId(externalId).build();
    }

    private static ProjectView successProjectView(String projectId, String externalId, List<ProjectView> subProjects) {
        return ProjectView.builder().projectId(projectId).externalProjectId(externalId).subProjects(subProjects).build();
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

    // -------------------------------------------------------------------------
    // Projects file — create vs. update
    // -------------------------------------------------------------------------

    @Test
    void projectsFile_createsNewRootProject_whenItDoesNotExistYet() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "PROJ-A")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isEqualTo(1);
        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();

        ArgumentCaptor<ProjectWithMilestonesCreateRequest> captor = ArgumentCaptor.forClass(ProjectWithMilestonesCreateRequest.class);
        verify(projectService).createWithMilestones(captor.capture());
        assertThat(captor.getValue().getExternalProjectId()).isEqualTo("PROJ-A");
        verify(projectService, never()).updateProject(any(), any());
    }

    @Test
    void projectsFile_updatesExistingRootProject() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A Renamed", "120000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(projectService, never()).createWithMilestones(any());
        verify(projectService).updateProject(eq("p1"), argThat(req -> ORG_ID.equals(req.getOrganisationId())));
    }

    @Test
    void projectsFile_createsAndUpdatesSubProjectsIndependently() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));

        ProjectCsvLine newSubRow = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        newSubRow.setSubExternalProjectId("SUB-NEW");
        newSubRow.setSubProjectTitle("Sub New");
        newSubRow.setSubTotalAmount("40000.00");
        newSubRow.setSubCurrency("USD");

        ProjectCsvLine existingSubRow = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        existingSubRow.setSubExternalProjectId("SUB-EXISTING");
        existingSubRow.setSubTotalAmount("60000.00");

        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(newSubRow, existingSubRow)));
        // Root already exists.
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        // SUB-NEW doesn't exist yet -> create.
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-NEW")).thenReturn(List.of());
        when(projectStructureService.createSubProject(any(), eq("SUB-NEW"), eq("Sub New"), any(), any(), any()))
                .thenReturn(Either.right(projectEntity("s-new", "SUB-NEW")));
        // SUB-EXISTING already exists -> update.
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-EXISTING"))
                .thenReturn(List.of(projectEntity("s-existing", "SUB-EXISTING")));
        when(projectService.updateProject(eq("s-existing"), any())).thenReturn(successProjectView("s-existing", "SUB-EXISTING", List.of()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isEqualTo(1); // root
        assertThat(result.getSubProjectsCreated()).isEqualTo(1); // SUB-NEW
        assertThat(result.getSubProjectsUpdated()).isEqualTo(1); // SUB-EXISTING
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(3); // root + 2 subs
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(projectStructureService).createSubProject(any(), eq("SUB-NEW"), eq("Sub New"), any(), any(), any());
        verify(projectService).updateProject(eq("s-existing"), any());
    }

    @Test
    void projectsFile_invalidRootRowIsSkippedButOthersStillProcess() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));

        ProjectCsvLine badRow = projectLine("PROJ-BAD", "", "100000.00", "USD"); // blank title, doesn't exist -> can't create
        ProjectCsvLine goodRow = projectLine("PROJ-GOOD", "Project Good", "50000.00", "USD");

        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(badRow, goodRow)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-BAD")).thenReturn(List.of());
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-GOOD")).thenReturn(List.of());
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1", "PROJ-GOOD", List.of()));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "PROJ-GOOD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getRowNumber()).isEqualTo(1);
        verify(projectService, times(1)).createWithMilestones(any());
    }

    // -------------------------------------------------------------------------
    // Milestones file — create vs. update; project is never auto-created
    // -------------------------------------------------------------------------

    @Test
    void milestonesFile_createsNewMilestone_whenItDoesNotExistYet() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));

        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setExternalMilestoneId("MS-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");

        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1")).thenReturn(Optional.empty());
        when(milestoneService.createMilestone(eq("s1"), eq(ORG_ID), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(1);
        assertThat(result.getMilestonesUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService).createMilestone(eq("s1"), eq(ORG_ID), any());
        verify(milestoneService, never()).updateMilestone(any(), any(), any(), any());
    }

    @Test
    void milestonesFile_updatesExistingMilestone_byExternalMilestoneId() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));

        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setExternalMilestoneId("MS-1");
        line.setMilestoneAmount("25000.00"); // only the amount changed; other cells left blank

        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        MilestoneEntity existingMilestone = MilestoneEntity.builder().id("m1").externalMilestoneId("MS-1").build();
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1")).thenReturn(Optional.of(existingMilestone));
        when(milestoneService.updateMilestone(eq("s1"), eq("m1"), eq(ORG_ID), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getMilestonesUpdated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService, never()).createMilestone(any(), any(), any());
        verify(milestoneService).updateMilestone(eq("s1"), eq("m1"), eq(ORG_ID), any());
    }

    @Test
    void milestonesFile_unknownProjectReference_reportsRowErrorAndNeverCreatesTheProject() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));

        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("UNKNOWN");
        line.setExternalMilestoneId("MS-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");

        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "UNKNOWN")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
        verify(milestoneService, never()).updateMilestone(any(), any(), any(), any());
        verify(projectService, never()).createWithMilestones(any());
    }

    // -------------------------------------------------------------------------
    // Events file — validation only, never creates a project or milestone
    // -------------------------------------------------------------------------

    @Test
    void eventsFile_groupsAllocationRowsIntoOneEvent_acrossTwoRootProjects() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = new EventCsvLine();
        row1.setEventType("SPENDING");
        row1.setFundingId("GRANT-1");
        row1.setCurrencyRcy("USD");
        row1.setExternalProjectId("PROJ-A");
        row1.setExternalMilestoneId("MS-1");
        row1.setAllocatedAmount("10000.00");

        EventCsvLine row2 = new EventCsvLine();
        row2.setEventType("SPENDING");
        row2.setFundingId("GRANT-1");
        row2.setCurrencyRcy("USD");
        row2.setExternalProjectId("PROJ-B");
        row2.setExternalMilestoneId("MS-2");
        row2.setAllocatedAmount("5000.00");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-B")).thenReturn(List.of(projectEntity("p2", "PROJ-B")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("p1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("p2", "MS-2"))
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
        assertThat(captor.getValue().getAllocations().get(0).getProjectTitle()).isNull(); // no on-the-fly creation data
        assertThat(captor.getValue().getAllocations().get(0).getMilestones()).hasSize(1);
        assertThat(captor.getValue().getAllocations()).allSatisfy(a -> assertThat(a.getSubProjects()).isEmpty());
    }

    @Test
    void eventsFile_nestsSubProjectAllocationUnderItsRoot() {
        // Regression test: a sub-project reference must be nested under its root via `subProjects` —
        // the underlying event-creation logic only resolves a flat externalProjectId as a ROOT project,
        // so passing a sub-project's id there would wrongly report it as "not found".
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");

        ProjectEntity root = projectEntity("p1", "PROJ-A");
        ProjectEntity sub = ProjectEntity.builder().id("s1").organisationId(ORG_ID).externalProjectId("SUB-1").parentProject(root).build();

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(sub));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getEventsCreated()).isEqualTo(1);

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        assertThat(captor.getValue().getAllocations()).hasSize(1);
        assertThat(captor.getValue().getAllocations().get(0).getExternalProjectId()).isEqualTo("PROJ-A");
        assertThat(captor.getValue().getAllocations().get(0).getMilestones()).isEmpty();
        assertThat(captor.getValue().getAllocations().get(0).getSubProjects()).hasSize(1);
        assertThat(captor.getValue().getAllocations().get(0).getSubProjects().get(0).getExternalProjectId()).isEqualTo("SUB-1");
        assertThat(captor.getValue().getAllocations().get(0).getSubProjects().get(0).getMilestones()).hasSize(1);
    }

    @Test
    void eventsFile_mergesTwoSubProjectsUnderTheSameRoot() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row1 = new EventCsvLine();
        row1.setEventType("SPENDING");
        row1.setFundingId("GRANT-1");
        row1.setCurrencyRcy("USD");
        row1.setExternalProjectId("SUB-1");
        row1.setExternalMilestoneId("MS-1");
        row1.setAllocatedAmount("10000.00");

        EventCsvLine row2 = new EventCsvLine();
        row2.setEventType("SPENDING");
        row2.setFundingId("GRANT-1");
        row2.setCurrencyRcy("USD");
        row2.setExternalProjectId("SUB-2");
        row2.setExternalMilestoneId("MS-2");
        row2.setAllocatedAmount("5000.00");

        ProjectEntity root = projectEntity("p1", "PROJ-A");
        ProjectEntity sub1 = ProjectEntity.builder().id("s1").organisationId(ORG_ID).externalProjectId("SUB-1").parentProject(root).build();
        ProjectEntity sub2 = ProjectEntity.builder().id("s2").organisationId(ORG_ID).externalProjectId("SUB-2").parentProject(root).build();

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(sub1));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-2")).thenReturn(List.of(sub2));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(root));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s2", "MS-2"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m2").build()));
        when(spendingEventService.createEvent(any())).thenReturn(SpendingEventView.builder().eventId("e1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();

        ArgumentCaptor<SpendingEventCreateRequest> captor = ArgumentCaptor.forClass(SpendingEventCreateRequest.class);
        verify(spendingEventService).createEvent(captor.capture());
        // One root allocation, both sub-projects nested under it — not two separate root allocations.
        assertThat(captor.getValue().getAllocations()).hasSize(1);
        assertThat(captor.getValue().getAllocations().get(0).getExternalProjectId()).isEqualTo("PROJ-A");
        assertThat(captor.getValue().getAllocations().get(0).getSubProjects()).hasSize(2);
    }

    @Test
    void eventsFile_missingProject_reportsErrorAndNeverCreatesAnything() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));

        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("UNKNOWN");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "UNKNOWN")).thenReturn(List.of());

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

        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("UNKNOWN-MS");
        row.setAllocatedAmount("10000.00");

        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "UNKNOWN-MS")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    // -------------------------------------------------------------------------
    // Ordering and dry run
    // -------------------------------------------------------------------------

    @Test
    void filesAreProcessedInProjectsThenMilestonesThenEventsOrder() {
        MultipartFile eventsFile = file("events.csv");
        MultipartFile projectsFile = file("projects.csv");
        when(csvTypeDetector.detect(eventsFile)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(csvTypeDetector.detect(projectsFile)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(projectsFile, ProjectCsvLine.class)).thenReturn(Either.right(List.of()));
        when(eventCsvParser.parseCsv(eventsFile, EventCsvLine.class)).thenReturn(Either.right(List.of()));

        // Uploaded in reverse order — the service must still process Projects before Events.
        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID)
                .files(List.of(eventsFile, projectsFile)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles()).extracting(FundingFileImportResult::getFileType)
                .containsExactly(FundingCsvFileType.PROJECTS, FundingCsvFileType.EVENTS);
    }

    @Test
    void dryRun_flowsThroughSameProcessingPath() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "PROJ-A")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).dryRun(true).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        // Note: true "nothing persisted" behavior depends on the real transaction manager rolling
        // back FundingBulkImportTransactionRunner's transaction — not observable with mocked
        // collaborators, so this is covered by an integration test instead. Here we confirm the
        // dry-run flag is threaded through and the same translation/counting logic still runs.
        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getProjectsCreated()).isEqualTo(1);
        verify(projectService, times(1)).createWithMilestones(any());
    }

    // -------------------------------------------------------------------------
    // Projects file — additional validation branches
    // -------------------------------------------------------------------------

    @Test
    void projectsFile_csvParseFailure_reportsFileLevelError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class))
                .thenReturn(Either.left(problem(HttpStatus.BAD_REQUEST, "CSV_PARSING_ERROR")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isEqualTo(FundingCsvFileType.PROJECTS);
        assertThat(fileResult.getRowsSucceeded()).isZero();
        assertThat(fileResult.getRowErrors()).hasSize(1);
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void projectsFile_blankExternalProjectId_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("", "Some Title", "1000.00", "USD"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectRepository, never()).findByOrganisationIdAndExternalProjectId(any(), any());
    }

    @Test
    void projectsFile_ambiguousRootReference_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A"), projectEntity("p2", "PROJ-A")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        assertThat(result.getFiles().get(0).getRowErrors().get(0).getReason()).contains("PROJ-A");
        verify(projectService, never()).createWithMilestones(any());
        verify(projectService, never()).updateProject(any(), any());
    }

    @ParameterizedTest(name = "project create fails when totalAmount=\"{0}\" currency=\"{1}\"")
    @CsvSource({
            "not-a-number, USD",
            "100000.00,    ''",
            "'',           USD"
    })
    void projectsFile_createInvalidOrMissingAmountOrCurrency_reportsError(String totalAmount, String currency) {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", totalAmount, currency))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void projectsFile_createsNewRootProjectAsSubProjectOfExistingParent() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-CHILD", "Child Project", "20000.00", "USD");
        line.setParentExternalProjectId("PROJ-PARENT");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-CHILD")).thenReturn(List.of());
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-PARENT"))
                .thenReturn(List.of(projectEntity("p-parent", "PROJ-PARENT")));
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p-child", "PROJ-CHILD", List.of()));
        when(projectRepository.findById("p-child")).thenReturn(Optional.of(projectEntity("p-child", "PROJ-CHILD")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getProjectsCreated()).isEqualTo(1);

        ArgumentCaptor<ProjectWithMilestonesCreateRequest> captor = ArgumentCaptor.forClass(ProjectWithMilestonesCreateRequest.class);
        verify(projectService).createWithMilestones(captor.capture());
        assertThat(captor.getValue().getParentProjectId()).isEqualTo("p-parent");
    }

    @Test
    void projectsFile_parentReferenceNotFound_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-CHILD", "Child Project", "20000.00", "USD");
        line.setParentExternalProjectId("PROJ-MISSING");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-CHILD")).thenReturn(List.of());
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-MISSING")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectService, never()).createWithMilestones(any());
    }

    @Test
    void projectsFile_updateWithReparenting_success() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setParentExternalProjectId("PROJ-PARENT");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-PARENT"))
                .thenReturn(List.of(projectEntity("p-parent", "PROJ-PARENT")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        verify(projectService).updateProject(eq("p1"), any());
    }

    @Test
    void projectsFile_subProjectInvalidAmount_reportsErrorButRootStillSucceeds() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setSubExternalProjectId("SUB-1");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("not-a-number");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
    }

    @Test
    void projectsFile_subProjectMissingTitleOnCreate_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setSubExternalProjectId("SUB-1");
        line.setSubTotalAmount("40000.00"); // no sub title supplied
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "PROJ-A")));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
    }

    @Test
    void projectsFile_subProjectCreateFails_rootStillSucceeds() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setSubExternalProjectId("SUB-1");
        line.setSubProjectTitle("Sub One");
        line.setSubTotalAmount("40000.00");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());
        when(projectService.createWithMilestones(any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findById("p1")).thenReturn(Optional.of(projectEntity("p1", "PROJ-A")));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of());
        when(projectStructureService.createSubProject(any(), eq("SUB-1"), eq("Sub One"), any(), any(), any()))
                .thenReturn(Either.left(problem(HttpStatus.CONFLICT, "PROJECT_TITLE_ALREADY_EXISTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isEqualTo(1);
        assertThat(result.getSubProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowsSucceeded()).isEqualTo(1); // root only
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void projectsFile_subProjectUpdateFails_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setSubExternalProjectId("SUB-1");
        line.setSubTotalAmount("40000.00");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(projectService.updateProject(eq("s1"), any()))
                .thenReturn(ProjectView.error(problem(HttpStatus.CONFLICT, "SPENDING_EVENT_ALREADY_PUBLISHED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        assertThat(result.getSubProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Milestones file — additional validation branches
    // -------------------------------------------------------------------------

    @Test
    void milestonesFile_csvParseFailure_reportsFileLevelError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class))
                .thenReturn(Either.left(problem(HttpStatus.BAD_REQUEST, "CSV_PARSING_ERROR")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        FundingFileImportResult fileResult = result.getFiles().get(0);
        assertThat(fileResult.getFileType()).isEqualTo(FundingCsvFileType.MILESTONES);
        assertThat(fileResult.getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_blankExternalProjectId_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectRepository, never()).findByOrganisationIdAndExternalProjectId(any(), any());
    }

    @Test
    void milestonesFile_invalidAmount_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("not-a-number");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_invalidDate_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("not-a-date");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_updateFails_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setExternalMilestoneId("MS-1");
        line.setMilestoneAmount("25000.00");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        MilestoneEntity existing = MilestoneEntity.builder().id("m1").externalMilestoneId("MS-1").build();
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1")).thenReturn(Optional.of(existing));
        when(milestoneService.updateMilestone(eq("s1"), eq("m1"), any(), any()))
                .thenReturn(MilestoneView.error(problem(HttpStatus.CONFLICT, "SPENDING_EVENT_ALREADY_PUBLISHED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void milestonesFile_createMissingRequiredFields_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setExternalMilestoneId("MS-NEW"); // doesn't exist yet, but the other required fields are blank
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-NEW")).thenReturn(Optional.empty());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_anonymousMilestone_resolvesOrCreates() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine(); // no externalMilestoneId
        line.setExternalProjectId("SUB-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity project = projectEntity("s1", "SUB-1");
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(project));
        when(projectRepository.findById("s1")).thenReturn(Optional.of(project));
        when(milestoneService.resolveOrCreate(eq(project), any())).thenReturn(Either.right(MilestoneEntity.builder().id("m1").build()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(1);
        assertThat(result.getFiles().get(0).getRowErrors()).isEmpty();
        verify(milestoneService, never()).createMilestone(any(), any(), any());
        verify(milestoneService).resolveOrCreate(eq(project), any());
    }

    @Test
    void milestonesFile_anonymousMilestone_resolveOrCreateFails_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        ProjectEntity project = projectEntity("s1", "SUB-1");
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(project));
        when(projectRepository.findById("s1")).thenReturn(Optional.of(project));
        when(milestoneService.resolveOrCreate(eq(project), any()))
                .thenReturn(Either.left(problem(HttpStatus.CONFLICT, "MILESTONE_TITLE_ALREADY_EXISTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void milestonesFile_createFails_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId("SUB-1");
        line.setExternalMilestoneId("MS-1");
        line.setMilestoneTitle("Milestone One");
        line.setMilestoneAmount("20000.00");
        line.setCurrency("USD");
        line.setMilestoneDate("2026-06-30");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1")).thenReturn(Optional.empty());
        when(milestoneService.createMilestone(eq("s1"), any(), any()))
                .thenReturn(MilestoneView.error(problem(HttpStatus.CONFLICT, "MILESTONE_TITLE_ALREADY_EXISTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Events file — additional validation branches
    // -------------------------------------------------------------------------

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
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));
        when(spendingEventService.createEvent(any()))
                .thenReturn(SpendingEventView.error(problem(HttpStatus.BAD_REQUEST, "SPEND_NOT_FULLY_ALLOCATED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getEventsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void eventsFile_missingEventTypeFundingIdOrCurrency_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setFundingId("GRANT-1"); // no eventType, no currencyRcy
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidEventType_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setEventType("NOT_A_TYPE");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidEventDate_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setEventDate("not-a-date");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
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
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setAmountFcy("not-a-number");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_blankExternalProjectIdInAllocationRow_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId(""); // blank
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_blankExternalMilestoneIdInAllocationRow_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId(""); // blank
        row.setAllocatedAmount("10000.00");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(projectEntity("s1", "SUB-1")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidAllocatedAmount_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = new EventCsvLine();
        row.setEventType("SPENDING");
        row.setFundingId("GRANT-1");
        row.setCurrencyRcy("USD");
        row.setExternalProjectId("SUB-1");
        row.setExternalMilestoneId("MS-1");
        row.setAllocatedAmount("not-a-number");
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(List.of(row)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    // -------------------------------------------------------------------------
    // Projects file — remaining branches (blank-vs-invalid distinction, business errors, ambiguity)
    // -------------------------------------------------------------------------

    @Test
    void projectsFile_rootCreateBusinessError_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A")).thenReturn(List.of());
        when(projectService.createWithMilestones(any()))
                .thenReturn(ProjectView.error(problem(HttpStatus.CONFLICT, "PROJECT_TITLE_ALREADY_EXISTS")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsCreated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectRepository, never()).findById("PROJ-A");
    }

    @Test
    void projectsFile_rootUpdateBusinessError_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(
                List.of(projectLine("PROJ-A", "Project A", "100000.00", "USD"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any()))
                .thenReturn(ProjectView.error(problem(HttpStatus.CONFLICT, "SPENDING_EVENT_ALREADY_PUBLISHED")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
    }

    @Test
    void projectsFile_subProjectAmbiguousReference_reportsError() {
        MultipartFile file = file("projects.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.PROJECTS));
        ProjectCsvLine line = projectLine("PROJ-A", "Project A", "100000.00", "USD");
        line.setSubExternalProjectId("SUB-AMBIG");
        line.setSubProjectTitle("Sub Ambiguous");
        line.setSubTotalAmount("40000.00");
        when(projectCsvParser.parseCsv(file, ProjectCsvLine.class)).thenReturn(Either.right(List.of(line)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "PROJ-A"))
                .thenReturn(List.of(projectEntity("p1", "PROJ-A")));
        when(projectService.updateProject(eq("p1"), any())).thenReturn(successProjectView("p1", "PROJ-A", List.of()));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-AMBIG"))
                .thenReturn(List.of(projectEntity("s1", "SUB-AMBIG"), projectEntity("s2", "SUB-AMBIG")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getProjectsUpdated()).isEqualTo(1);
        assertThat(result.getSubProjectsCreated()).isZero();
        assertThat(result.getSubProjectsUpdated()).isZero();
        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(projectStructureService, never()).createSubProject(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Milestones file — remaining required-field branches, ambiguity, and cache reuse
    // -------------------------------------------------------------------------

    private static MilestoneCsvLine milestoneLine(String projectId, String title, String amount, String currency, String date) {
        MilestoneCsvLine line = new MilestoneCsvLine();
        line.setExternalProjectId(projectId);
        line.setMilestoneTitle(title);
        line.setMilestoneAmount(amount);
        line.setCurrency(currency);
        line.setMilestoneDate(date);
        return line;
    }

    @ParameterizedTest(name = "milestone create fails when amount=\"{0}\" currency=\"{1}\" date=\"{2}\"")
    @CsvSource({
            "'',       USD, 2026-06-30",
            "20000.00, '',  2026-06-30",
            "20000.00, USD, ''"
    })
    void milestonesFile_createMissingRequiredField_reportsError(String amount, String currency, String date) {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(milestoneLine("SUB-1", "Milestone One", amount, currency, date))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_ambiguousProjectReference_reportsError() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(
                List.of(milestoneLine("SUB-AMBIG", "Milestone One", "20000.00", "USD", "2026-06-30"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-AMBIG"))
                .thenReturn(List.of(projectEntity("s1", "SUB-AMBIG"), projectEntity("s2", "SUB-AMBIG")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(milestoneService, never()).createMilestone(any(), any(), any());
    }

    @Test
    void milestonesFile_reusesCachedProjectResolutionAcrossRows() {
        MultipartFile file = file("milestones.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.MILESTONES));
        MilestoneCsvLine row1 = milestoneLine("SUB-1", "Milestone One", "20000.00", "USD", "2026-06-30");
        row1.setExternalMilestoneId("MS-1");
        MilestoneCsvLine row2 = milestoneLine("SUB-1", "Milestone Two", "20000.00", "USD", "2026-07-15");
        row2.setExternalMilestoneId("MS-2");
        when(milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class)).thenReturn(Either.right(List.of(row1, row2)));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1"))
                .thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId(eq("s1"), any())).thenReturn(Optional.empty());
        when(milestoneService.createMilestone(eq("s1"), any(), any())).thenReturn(MilestoneView.builder().milestoneId("m1").build());

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getMilestonesCreated()).isEqualTo(2);
        // Second row's project lookup should be served from the in-call cache, not a second query.
        verify(projectRepository, times(1)).findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1");
    }

    // -------------------------------------------------------------------------
    // Events file — remaining required-field branches, invalid FX/RCY parsing, blank allocation, ambiguity
    // -------------------------------------------------------------------------

    private static EventCsvLine eventLine(String eventType, String fundingId, String currencyRcy,
            String externalProjectId, String externalMilestoneId, String allocatedAmount) {
        EventCsvLine line = new EventCsvLine();
        line.setEventType(eventType);
        line.setFundingId(fundingId);
        line.setCurrencyRcy(currencyRcy);
        line.setExternalProjectId(externalProjectId);
        line.setExternalMilestoneId(externalMilestoneId);
        line.setAllocatedAmount(allocatedAmount);
        return line;
    }

    @Test
    void eventsFile_missingOnlyFundingId_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("SPENDING", "", "USD", "SUB-1", "MS-1", "10000.00"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_missingOnlyCurrencyRcy_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("SPENDING", "GRANT-1", "", "SUB-1", "MS-1", "10000.00"))));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_invalidFxRate_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "SUB-1", "MS-1", "10000.00");
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
        EventCsvLine row = eventLine("SPENDING", "GRANT-1", "USD", "SUB-1", "MS-1", "10000.00");
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
                List.of(eventLine("SPENDING", "GRANT-1", "USD", "SUB-1", "MS-1", ""))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-1")).thenReturn(List.of(projectEntity("s1", "SUB-1")));
        when(milestoneService.findByProjectIdAndExternalMilestoneId("s1", "MS-1"))
                .thenReturn(Optional.of(MilestoneEntity.builder().id("m1").build()));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

    @Test
    void eventsFile_ambiguousProjectReference_reportsError() {
        MultipartFile file = file("events.csv");
        when(csvTypeDetector.detect(file)).thenReturn(Optional.of(FundingCsvFileType.EVENTS));
        when(eventCsvParser.parseCsv(file, EventCsvLine.class)).thenReturn(Either.right(
                List.of(eventLine("SPENDING", "GRANT-1", "USD", "SUB-AMBIG", "MS-1", "10000.00"))));
        when(projectRepository.findByOrganisationIdAndExternalProjectId(ORG_ID, "SUB-AMBIG"))
                .thenReturn(List.of(projectEntity("s1", "SUB-AMBIG"), projectEntity("s2", "SUB-AMBIG")));

        BulkImportRequest request = BulkImportRequest.builder().organisationId(ORG_ID).files(List.of(file)).build();
        FundingBulkImportResult result = bulkImportService.importFiles(request);

        assertThat(result.getFiles().get(0).getRowErrors()).hasSize(1);
        verify(spendingEventService, never()).createEvent(any());
    }

}
