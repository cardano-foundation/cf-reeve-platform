package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.csv.EventCsvLine;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvTypeDetector;
import org.cardanofoundation.lob.app.funding.domain.csv.MilestoneCsvLine;
import org.cardanofoundation.lob.app.funding.domain.csv.ProjectCsvLine;
import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;
import org.cardanofoundation.lob.app.funding.domain.request.BulkImportRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventMilestoneAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventProjectAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.EventSubProjectAllocationRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.SpendingEventCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingFileImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowError;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

/**
 * Bulk CSV import for the funding module. Accepts up to three files (Projects, Milestones, Events —
 * type auto-detected from headers, any subset, any count) and translates each row/group into the
 * existing JSON-API request DTOs, then calls the existing, already-validated service methods — this
 * class does not reimplement any funding business rule, it only parses CSV rows into those requests.
 *
 * <p><b>Upsert semantics</b> (so re-uploading a file with one changed row is safe):
 * <ul>
 *   <li>Projects file: a project (root or sub-project) is <em>created</em> when its
 *   {@code externalProjectId} doesn't exist yet, or <em>updated</em> in place when it does. Root and
 *   each sub-project row are resolved and upserted independently, so one bad row doesn't block its
 *   siblings — with one exception: if the root is newly created by this call and every sub-project row
 *   that tried to attach to it fails, the root is rolled back too rather than left as a childless
 *   orphan (see {@link FundingProjectGroupTransactionRunner}). A root row with no sub-project columns
 *   at all, or an already-existing root, is never rolled back this way.</li>
 *   <li>Milestones file: same — created when {@code externalMilestoneId} doesn't exist under the
 *   referenced project, updated when it does. The referenced project itself is never created here —
 *   a missing project is always a row error (create it via the Projects file first).</li>
 *   <li>Events file: pure validation, no creation. Both {@code externalProjectId} and
 *   {@code externalMilestoneId} must already exist — this file carries only allocation amounts, not
 *   enough data to create a project or milestone from scratch.</li>
 * </ul>
 *
 * <p><b>Partial-save semantics:</b> this orchestrator is deliberately <em>not</em> {@code @Transactional}.
 * Each call into {@code ProjectService}/{@code ProjectStructureService}/{@code MilestoneService}/
 * {@code SpendingEventService} is a call to another Spring-proxied bean, each already
 * {@code @Transactional} on its own — so every project row, milestone row, and event group commits
 * (or rolls back) independently. A bad row anywhere never undoes rows that already succeeded.
 *
 * <p><b>Dry run:</b> there is no "validate without saving" mode on the underlying services (they
 * persist eagerly as they resolve-or-create). Reusing the exact same code path for previews (rather
 * than re-deriving the validation rules) is what keeps the preview numbers trustworthy: for
 * {@code dryRun}, {@link #processFiles} runs inside {@link FundingBulkImportTransactionRunner}'s
 * single wrapping transaction, which is unconditionally rolled back at the end.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingBulkImportService {

    private final CsvParser<ProjectCsvLine> projectCsvParser;
    private final CsvParser<MilestoneCsvLine> milestoneCsvParser;
    private final CsvParser<EventCsvLine> eventCsvParser;
    private final FundingCsvTypeDetector csvTypeDetector;
    private final FundingProjectRepository projectRepository;
    private final ProjectService projectService;
    private final ProjectStructureService projectStructureService;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final FundingBulkImportTransactionRunner transactionRunner;
    private final FundingProjectGroupTransactionRunner projectGroupTransactionRunner;

    public FundingBulkImportResult importFiles(BulkImportRequest request) {
        if (organisationPublicApi.findByOrganisationId(request.getOrganisationId()).isEmpty()) {
            return FundingBulkImportResult.error(Problems.organisationNotFound(request.getOrganisationId()));
        }
        if (request.getFiles() == null || request.getFiles().isEmpty()) {
            return FundingBulkImportResult.error(Problems.badRequest(
                    "At least one CSV file is required", ErrorTitleConstants.NO_FILES_UPLOADED));
        }

        if (request.isDryRun()) {
            return transactionRunner.runAndRollBack(() -> processFiles(request));
        }
        return processFiles(request);
    }

    private FundingBulkImportResult processFiles(BulkImportRequest request) {
        String organisationId = request.getOrganisationId();
        // Resolved externalProjectId -> internal project id, shared across all three files in this
        // call so Milestones/Events can see projects created/updated earlier in the same request.
        Map<String, String> resolvedProjectIds = new HashMap<>();

        List<FileWithType> filesInOrder = request.getFiles().stream()
                .map(file -> new FileWithType(file, csvTypeDetector.detect(file)))
                .sorted(Comparator.comparing(f -> f.type().map(Enum::ordinal).orElse(Integer.MAX_VALUE)))
                .toList();

        List<FundingFileImportResult> fileResults = new ArrayList<>();
        FundingTotals totals = new FundingTotals();

        for (FileWithType f : filesInOrder) {
            Optional<FundingCsvFileType> maybeType = f.type();
            if (maybeType.isEmpty()) {
                fileResults.add(unrecognizedFileResult(f.file()));
                continue;
            }
            switch (maybeType.get()) {
                case PROJECTS -> {
                    ProjectsFileOutcome outcome = processProjectsFile(organisationId, f.file(), resolvedProjectIds);
                    fileResults.add(outcome.fileResult());
                    totals.addProjects(outcome);
                }
                case MILESTONES -> {
                    MilestonesFileOutcome outcome = processMilestonesFile(organisationId, f.file(), resolvedProjectIds);
                    fileResults.add(outcome.fileResult());
                    totals.addMilestones(outcome);
                }
                case EVENTS -> {
                    EventsFileOutcome outcome = processEventsFile(organisationId, f.file(), resolvedProjectIds);
                    fileResults.add(outcome.fileResult());
                    totals.addEvents(outcome);
                }
            }
        }

        return totals.toResult(request.isDryRun(), fileResults);
    }

    private static FundingFileImportResult unrecognizedFileResult(MultipartFile file) {
        return FundingFileImportResult.builder()
                .fileName(file.getOriginalFilename())
                .fileType(null)
                .rowsSucceeded(0)
                .rowErrors(List.of(FundingRowError.builder()
                        .rowNumber(0)
                        .reason("Unable to determine file type from its headers — check it matches one of the downloadable templates")
                        .build()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Projects file — upsert: create root/sub-projects that don't exist, update the ones that do.
    // Root and each sub-project row are resolved and saved independently.
    // -------------------------------------------------------------------------

    private ProjectsFileOutcome processProjectsFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<ProjectCsvLine>> parsed = projectCsvParser.parseCsv(file, ProjectCsvLine.class);
        if (parsed.isLeft()) {
            return new ProjectsFileOutcome(fileLevelError(file, FundingCsvFileType.PROJECTS, parsed.getLeft()), 0, 0, 0, 0);
        }

        List<ProjectCsvLine> lines = parsed.get();
        // Group consecutive/duplicate rows sharing the same root externalProjectId — the root is
        // upserted once per group, and each grouped row's sub* columns are upserted as their own
        // sub-project, independently of one another and of the root's own outcome.
        LinkedHashMap<String, List<Integer>> groups = groupByKey(lines, ProjectCsvLine::getExternalProjectId);

        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 0;
        int projectsCreated = 0;
        int projectsUpdated = 0;
        int subProjectsCreated = 0;
        int subProjectsUpdated = 0;

        for (List<Integer> idxs : groups.values()) {
            ProjectGroupOutcome outcome = projectGroupTransactionRunner.runInTransaction(
                    () -> processProjectGroup(organisationId, lines, idxs, resolvedProjectIds));
            errors.addAll(outcome.errors());
            succeeded += outcome.succeeded();
            projectsCreated += outcome.projectsCreated();
            projectsUpdated += outcome.projectsUpdated();
            subProjectsCreated += outcome.subProjectsCreated();
            subProjectsUpdated += outcome.subProjectsUpdated();
        }

        return new ProjectsFileOutcome(
                FundingFileImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .fileType(FundingCsvFileType.PROJECTS)
                        .rowsSucceeded(succeeded)
                        .rowErrors(errors)
                        .build(),
                projectsCreated, subProjectsCreated, projectsUpdated, subProjectsUpdated);
    }

    /**
     * Upserts one group's root row, then each of its sub-project rows independently of the others.
     * Exception: if the root is newly created by this call and every sub-project row that tried to
     * attach to it failed, the whole group is reported as failed (see {@link ProjectGroupOutcome#orphanRoot()})
     * so the caller can roll the root back too, rather than leaving a childless root behind that the
     * user never asked for on its own. A root row with no sub-project columns at all is unaffected —
     * it never attempted a sub-project, so there is nothing to be "orphaned" from; it may legitimately
     * be waiting on milestones from a separate Milestones file. Likewise an already-existing root is
     * never rolled back here: it was already a valid project before this call.
     */
    private ProjectGroupOutcome processProjectGroup(String organisationId, List<ProjectCsvLine> lines,
            List<Integer> idxs, Map<String, String> resolvedProjectIds) {

        List<FundingRowError> errors = new ArrayList<>();
        int rootRowNumber = idxs.get(0) + 1;
        ProjectCsvLine root = lines.get(idxs.get(0));

        Either<ProblemDetail, UpsertOutcome<ProjectEntity>> rootResult = upsertRootProject(organisationId, root, resolvedProjectIds);
        if (rootResult.isLeft()) {
            errors.add(rowError(rootRowNumber, rootResult.getLeft()));
            return new ProjectGroupOutcome(errors, 0, 0, 0, 0, 0, false);
        }
        UpsertOutcome<ProjectEntity> rootOutcome = rootResult.get();

        int subProjectsCreated = 0;
        int subProjectsUpdated = 0;
        int succeeded = 1;
        boolean attemptedSubProject = false;
        int subProjectsSucceeded = 0;
        for (int idx : idxs) {
            ProjectCsvLine line = lines.get(idx);
            if (line.hasSubProject()) {
                attemptedSubProject = true;
                Either<ProblemDetail, UpsertOutcome<ProjectEntity>> subResult =
                        upsertSubProject(organisationId, rootOutcome.entity(), line, resolvedProjectIds);
                if (subResult.isLeft()) {
                    errors.add(rowError(idx + 1, subResult.getLeft()));
                } else {
                    if (subResult.get().created()) subProjectsCreated++; else subProjectsUpdated++;
                    subProjectsSucceeded++;
                    succeeded++;
                }
            }
        }

        boolean orphanRoot = rootOutcome.created() && attemptedSubProject && subProjectsSucceeded == 0;
        if (orphanRoot) {
            // The transaction wrapping this group will be rolled back — undo the cache entry so a
            // later Milestones/Events file in the same request doesn't resolve to a project id that
            // no longer exists.
            resolvedProjectIds.remove(root.getExternalProjectId());
            errors.add(rowError(rootRowNumber, Problems.badRequest(
                    "Project not created: every sub-project row for it failed (see row error(s) above)",
                    ErrorTitleConstants.PROJECT_NOT_CREATED_NO_SUBPROJECT)));
            return new ProjectGroupOutcome(errors, 0, 0, 0, 0, 0, true);
        }

        return new ProjectGroupOutcome(errors, succeeded,
                rootOutcome.created() ? 1 : 0, rootOutcome.created() ? 0 : 1,
                subProjectsCreated, subProjectsUpdated, false);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> upsertRootProject(String organisationId,
            ProjectCsvLine root, Map<String, String> resolvedProjectIds) {

        if (isBlank(root.getExternalProjectId())) {
            return Either.left(Problems.badRequest("External Project ID is required", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProject(organisationId, root.getExternalProjectId());
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        Optional<ProjectEntity> existing = existingE.get();

        Either<ProblemDetail, BigDecimal> totalAmountE = parseDecimal(root.getTotalAmount(), "Total Amount");
        if (totalAmountE.isLeft()) {
            return Either.left(totalAmountE.getLeft());
        }

        Either<ProblemDetail, String> parentIdE = resolveOptionalParentId(organisationId, root.getParentExternalProjectId(), resolvedProjectIds);
        if (parentIdE.isLeft()) {
            return Either.left(parentIdE.getLeft());
        }

        return existing.isEmpty()
                ? createRootProject(organisationId, root, totalAmountE.get(), parentIdE.get(), resolvedProjectIds)
                : updateRootProject(root, existing.get(), totalAmountE.get(), parentIdE.get(), resolvedProjectIds);
    }

    /** Blank means "no parent" (a root project); otherwise resolves the referenced existing project's id. */
    private Either<ProblemDetail, String> resolveOptionalParentId(String organisationId, String parentExternalProjectId,
            Map<String, String> resolvedProjectIds) {
        if (isBlank(parentExternalProjectId)) {
            return Either.right(null);
        }
        return resolveProjectId(organisationId, parentExternalProjectId, resolvedProjectIds);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> createRootProject(String organisationId, ProjectCsvLine root,
            BigDecimal totalAmount, String parentId, Map<String, String> resolvedProjectIds) {

        // CREATE — full data is required.
        if (isBlank(root.getProjectTitle())) {
            return Either.left(Problems.badRequest("Project Title is required to create a new project", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        if (totalAmount == null) {
            return Either.left(Problems.badRequest("Total Amount is required to create a new project", ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        if (isBlank(root.getCurrency())) {
            return Either.left(Problems.badRequest("Currency is required to create a new project", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }

        ProjectWithMilestonesCreateRequest.ProjectWithMilestonesCreateRequestBuilder<?, ?> builder =
                ProjectWithMilestonesCreateRequest.builder()
                        .organisationId(organisationId)
                        .externalProjectId(root.getExternalProjectId())
                        .projectTitle(root.getProjectTitle())
                        .fundingId(blankToNull(root.getFundingId()))
                        .totalAmount(totalAmount)
                        .currency(root.getCurrency());
        if (parentId != null) {
            builder.parentProjectId(parentId);
        }
        // Sub-projects for this group are upserted separately, one row at a time — created empty here.
        ProjectView view = projectService.createWithMilestones(builder.build());
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        ProjectEntity created = projectRepository.findById(view.getProjectId()).orElseThrow();
        resolvedProjectIds.put(root.getExternalProjectId(), created.getId());
        return Either.right(new UpsertOutcome<>(created, true));
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> updateRootProject(ProjectCsvLine root, ProjectEntity existing,
            BigDecimal totalAmount, String parentId, Map<String, String> resolvedProjectIds) {

        // UPDATE — partial: a blank CSV cell means "leave this field unchanged".
        ProjectUpdateRequest updateRequest = ProjectUpdateRequest.builder()
                .projectTitle(blankToNull(root.getProjectTitle()))
                .totalAmount(totalAmount)
                .currency(blankToNull(root.getCurrency()))
                .parentProjectId(parentId)
                .build();
        ProjectView view = projectService.updateProject(existing.getId(), updateRequest);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        resolvedProjectIds.put(root.getExternalProjectId(), existing.getId());
        return Either.right(new UpsertOutcome<>(existing, false));
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> upsertSubProject(String organisationId,
            ProjectEntity rootEntity, ProjectCsvLine line, Map<String, String> resolvedProjectIds) {

        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProject(organisationId, line.getSubExternalProjectId());
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        Optional<ProjectEntity> existing = existingE.get();

        Either<ProblemDetail, BigDecimal> subAmountE = parseDecimal(line.getSubTotalAmount(), "Sub Total Amount");
        if (subAmountE.isLeft()) {
            return Either.left(subAmountE.getLeft());
        }

        return existing.isEmpty()
                ? createNewSubProject(rootEntity, line, subAmountE.get(), resolvedProjectIds)
                : updateSubProject(line, existing.get(), subAmountE.get(), resolvedProjectIds);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> createNewSubProject(ProjectEntity rootEntity, ProjectCsvLine line,
            BigDecimal subAmount, Map<String, String> resolvedProjectIds) {

        // CREATE — full data is required, matching the REST API's flat parentProjectId shape
        // (ProjectWithMilestonesCreateRequest: totalAmount @NotNull, currency @NotBlank), even though
        // the API's own nested subProjects shape (ProjectTreeNodeRequest) leaves both optional.
        if (isBlank(line.getSubProjectTitle())) {
            return Either.left(Problems.badRequest(
                    "Sub Project Title is required to create sub-project: " + line.getSubExternalProjectId(),
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        if (subAmount == null) {
            return Either.left(Problems.badRequest(
                    "Sub Total Amount is required to create sub-project: " + line.getSubExternalProjectId(),
                    ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        if (isBlank(line.getSubCurrency())) {
            return Either.left(Problems.badRequest(
                    "Sub Currency is required to create sub-project: " + line.getSubExternalProjectId(),
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Either<ProblemDetail, ProjectEntity> created = projectStructureService.createSubProject(
                rootEntity, line.getSubExternalProjectId(), line.getSubProjectTitle(),
                blankToNull(line.getSubFundingId()), subAmount, blankToNull(line.getSubCurrency()));
        if (created.isLeft()) {
            return Either.left(created.getLeft());
        }
        ProjectEntity entity = created.get();
        resolvedProjectIds.put(line.getSubExternalProjectId(), entity.getId());
        return Either.right(new UpsertOutcome<>(entity, true));
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> updateSubProject(ProjectCsvLine line, ProjectEntity existing,
            BigDecimal subAmount, Map<String, String> resolvedProjectIds) {

        ProjectUpdateRequest updateRequest = ProjectUpdateRequest.builder()
                .projectTitle(blankToNull(line.getSubProjectTitle()))
                .totalAmount(subAmount)
                .currency(blankToNull(line.getSubCurrency()))
                .build();
        ProjectView view = projectService.updateProject(existing.getId(), updateRequest);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        resolvedProjectIds.put(line.getSubExternalProjectId(), existing.getId());
        return Either.right(new UpsertOutcome<>(existing, false));
    }

    // -------------------------------------------------------------------------
    // Milestones file — upsert by externalMilestoneId; the target project must already exist.
    // -------------------------------------------------------------------------

    private MilestonesFileOutcome processMilestonesFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<MilestoneCsvLine>> parsed = milestoneCsvParser.parseCsv(file, MilestoneCsvLine.class);
        if (parsed.isLeft()) {
            return new MilestonesFileOutcome(fileLevelError(file, FundingCsvFileType.MILESTONES, parsed.getLeft()), 0, 0);
        }

        List<MilestoneCsvLine> lines = parsed.get();
        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 0;
        int created = 0;
        int updated = 0;

        for (int i = 0; i < lines.size(); i++) {
            int rowNumber = i + 1;
            Either<ProblemDetail, Boolean> result = upsertMilestoneRow(organisationId, lines.get(i), resolvedProjectIds);
            if (result.isLeft()) {
                errors.add(rowError(rowNumber, result.getLeft()));
                continue;
            }
            succeeded++;
            boolean wasCreated = result.get();
            if (wasCreated) created++; else updated++;
        }

        return new MilestonesFileOutcome(
                FundingFileImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .fileType(FundingCsvFileType.MILESTONES)
                        .rowsSucceeded(succeeded)
                        .rowErrors(errors)
                        .build(),
                created, updated);
    }

    /** Returns {@code Right(true)} when a new milestone was created, {@code Right(false)} when an existing one was updated. */
    private Either<ProblemDetail, Boolean> upsertMilestoneRow(String organisationId, MilestoneCsvLine line, Map<String, String> resolvedProjectIds) {
        if (isBlank(line.getExternalProjectId())) {
            return Either.left(Problems.badRequest("External Project ID is required", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        // The project is never created here — a missing reference is always a row error.
        Either<ProblemDetail, String> projectIdE = resolveProjectId(organisationId, line.getExternalProjectId(), resolvedProjectIds);
        if (projectIdE.isLeft()) {
            return Either.left(projectIdE.getLeft());
        }
        String projectId = projectIdE.get();

        Either<ProblemDetail, BigDecimal> amountE = parseDecimal(line.getMilestoneAmount(), "Milestone Amount");
        if (amountE.isLeft()) {
            return Either.left(amountE.getLeft());
        }
        Either<ProblemDetail, LocalDate> dateE = parseNullableDate(line.getMilestoneDate(), "Milestone Date");
        if (dateE.isLeft()) {
            return Either.left(dateE.getLeft());
        }
        BigDecimal amount = amountE.get();
        LocalDate date = dateE.get();

        if (!isBlank(line.getExternalMilestoneId())) {
            Optional<Either<ProblemDetail, Boolean>> updateResult = tryUpdateExistingMilestone(projectId, line, amount, date);
            if (updateResult.isPresent()) {
                return updateResult.get();
            }
        }
        return createNewMilestone(projectId, line, amount, date);
    }

    /** Empty when no milestone matches {@code line}'s externalMilestoneId — the caller should create one instead. */
    private Optional<Either<ProblemDetail, Boolean>> tryUpdateExistingMilestone(String projectId, MilestoneCsvLine line,
            BigDecimal amount, LocalDate date) {
        Optional<MilestoneEntity> existing = milestoneService.findByProjectIdAndExternalMilestoneId(projectId, line.getExternalMilestoneId());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        // UPDATE — partial: a blank CSV cell means "leave this field unchanged".
        MilestoneUpdateRequest updateRequest = MilestoneUpdateRequest.builder()
                .milestoneTitle(blankToNull(line.getMilestoneTitle()))
                .milestoneAmount(amount)
                .currency(blankToNull(line.getCurrency()))
                .milestoneDate(date)
                .build();
        MilestoneView view = milestoneService.updateMilestone(projectId, existing.get().getId(), updateRequest);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Optional.of(Either.left(error.get()));
        }
        return Optional.of(Either.right(false));
    }

    private Either<ProblemDetail, Boolean> createNewMilestone(String projectId, MilestoneCsvLine line, BigDecimal amount, LocalDate date) {
        // CREATE — full data is required.
        if (isBlank(line.getMilestoneTitle()) || amount == null || isBlank(line.getCurrency()) || date == null) {
            return Either.left(Problems.badRequest(
                    "milestoneTitle, milestoneAmount, currency and milestoneDate are required to create a new milestone",
                    ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED));
        }
        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .externalMilestoneId(blankToNull(line.getExternalMilestoneId()))
                .milestoneTitle(line.getMilestoneTitle())
                .milestoneAmount(amount)
                .currency(line.getCurrency())
                .milestoneDate(date)
                .build();

        if (isBlank(line.getExternalMilestoneId())) {
            // No stable key to upsert against — dedupe by content so an identical re-upload doesn't
            // fail as "already exists" (it just resolves to the same existing milestone, a no-op).
            ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
            Either<ProblemDetail, MilestoneEntity> result = milestoneService.resolveOrCreate(project, request);
            if (result.isLeft()) {
                return Either.left(result.getLeft());
            }
            return Either.right(true);
        }
        MilestoneView view = milestoneService.createMilestone(projectId, request);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        return Either.right(true);
    }

    // -------------------------------------------------------------------------
    // Events file — pure validation: both the project and the milestone must already exist.
    // -------------------------------------------------------------------------

    private EventsFileOutcome processEventsFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<EventCsvLine>> parsed = eventCsvParser.parseCsv(file, EventCsvLine.class);
        if (parsed.isLeft()) {
            return new EventsFileOutcome(fileLevelError(file, FundingCsvFileType.EVENTS, parsed.getLeft()), 0, 0);
        }

        List<EventCsvLine> lines = parsed.get();
        LinkedHashMap<String, List<Integer>> groups = groupByKey(lines, FundingBulkImportService::eventKey);

        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 0;
        int eventsCreated = 0;
        int allocationsCreated = 0;

        for (List<Integer> idxs : groups.values()) {
            List<EventCsvLine> group = idxs.stream().map(lines::get).toList();
            EventGroupOutcome outcome = processEventGroup(organisationId, idxs.get(0) + 1, group, resolvedProjectIds);
            if (outcome.error() != null) {
                errors.add(outcome.error());
                continue;
            }
            succeeded++;
            eventsCreated++;
            allocationsCreated += outcome.allocationsCreated();
        }

        return new EventsFileOutcome(
                FundingFileImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .fileType(FundingCsvFileType.EVENTS)
                        .rowsSucceeded(succeeded)
                        .rowErrors(errors)
                        .build(),
                eventsCreated, allocationsCreated);
    }

    private EventGroupOutcome processEventGroup(String organisationId, int firstRowNumber, List<EventCsvLine> group,
            Map<String, String> resolvedProjectIds) {

        Either<ProblemDetail, SpendingEventCreateRequest> built = buildEventRequest(organisationId, group, resolvedProjectIds);
        if (built.isLeft()) {
            return new EventGroupOutcome(rowError(firstRowNumber, built.getLeft()), 0);
        }

        SpendingEventView view = spendingEventService.createEvent(built.get());
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return new EventGroupOutcome(rowError(firstRowNumber, error.get()), 0);
        }
        return new EventGroupOutcome(null, group.size());
    }

    private static String eventKey(EventCsvLine line) {
        return String.join("||",
                nullToEmpty(line.getFundingId()), nullToEmpty(line.getEventType()),
                nullToEmpty(line.getFundingHash()), nullToEmpty(line.getCurrencyRcy()));
    }

    private Either<ProblemDetail, SpendingEventCreateRequest> buildEventRequest(String organisationId, List<EventCsvLine> group,
            Map<String, String> resolvedProjectIds) {

        Either<ProblemDetail, EventHeader> headerE = parseEventHeader(group.get(0));
        if (headerE.isLeft()) {
            return Either.left(headerE.getLeft());
        }

        Either<ProblemDetail, Map<String, List<EventCsvLine>>> byProjectE = groupAllocationRowsByProject(group);
        if (byProjectE.isLeft()) {
            return Either.left(byProjectE.getLeft());
        }

        Either<ProblemDetail, List<EventProjectAllocationRequest>> allocationsE =
                resolveAllocations(organisationId, byProjectE.get(), resolvedProjectIds);
        if (allocationsE.isLeft()) {
            return Either.left(allocationsE.getLeft());
        }

        return Either.right(buildSpendingEventRequest(organisationId, headerE.get(), allocationsE.get()));
    }

    /** Parses and validates the event-level (non-allocation) columns, shared by every row in the group. */
    private Either<ProblemDetail, EventHeader> parseEventHeader(EventCsvLine first) {
        if (isBlank(first.getEventType()) || isBlank(first.getFundingId()) || isBlank(first.getCurrencyRcy())) {
            return Either.left(Problems.badRequest(
                    "eventType, fundingId and currencyRcy are required", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        EventType eventType;
        try {
            eventType = EventType.valueOf(first.getEventType().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Either.left(Problems.badRequest(
                    "Invalid eventType: " + first.getEventType() + ". Options are: FUNDING, SPENDING, REFUND",
                    ErrorTitleConstants.CSV_ROW_INVALID));
        }

        Either<ProblemDetail, LocalDate> eventDate = parseNullableDate(first.getEventDate(), "Event Date");
        if (eventDate.isLeft()) return Either.left(eventDate.getLeft());
        Either<ProblemDetail, BigDecimal> amountFcy = parseDecimal(first.getAmountFcy(), "Amount FCY");
        if (amountFcy.isLeft()) return Either.left(amountFcy.getLeft());
        Either<ProblemDetail, BigDecimal> fxRate = parseDecimal(first.getFxRate(), "FX Rate");
        if (fxRate.isLeft()) return Either.left(fxRate.getLeft());
        Either<ProblemDetail, BigDecimal> amountRcy = parseDecimal(first.getAmountRcy(), "Amount RCY");
        if (amountRcy.isLeft()) return Either.left(amountRcy.getLeft());

        return Either.right(new EventHeader(eventType, first.getFundingId(), blankToNull(first.getFundingHash()),
                blankToNull(first.getFundingEntity()), first.getCurrencyRcy(), eventDate.get(),
                blankToNull(first.getCategory()), blankToNull(first.getVendor()), amountFcy.get(),
                blankToNull(first.getCurrencyFcy()), fxRate.get(), amountRcy.get(),
                blankToNull(first.getHash()), blankToNull(first.getNotes())));
    }

    /** Groups allocation rows by target project, preserving first-seen order, so a project that receives
     * several milestone allocations in this event appears once with all its milestones. */
    private Either<ProblemDetail, Map<String, List<EventCsvLine>>> groupAllocationRowsByProject(List<EventCsvLine> group) {
        LinkedHashMap<String, List<EventCsvLine>> byProject = new LinkedHashMap<>();
        for (EventCsvLine line : group) {
            if (isBlank(line.getExternalProjectId())) {
                return Either.left(Problems.badRequest("External Project ID is required for every allocation row",
                        ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
            }
            byProject.computeIfAbsent(line.getExternalProjectId(), k -> new ArrayList<>()).add(line);
        }
        return Either.right(byProject);
    }

    /**
     * Resolves each referenced project and builds its milestone allocations, nesting sub-project
     * references under their root — the underlying event-creation logic only resolves a flat
     * {@code externalProjectId} as a ROOT project, so a sub-project must be attached via its root's
     * {@code subProjects} instead of appearing as its own top-level allocation.
     */
    private Either<ProblemDetail, List<EventProjectAllocationRequest>> resolveAllocations(String organisationId,
            Map<String, List<EventCsvLine>> byProject, Map<String, String> resolvedProjectIds) {

        LinkedHashMap<String, ProjectEntity> rootsByExternalId = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot = new LinkedHashMap<>();

        for (List<EventCsvLine> projectLines : byProject.values()) {
            // Validation only — the project must already exist, this file never creates one.
            Either<ProblemDetail, ProjectEntity> projectE =
                    resolveExistingProjectEntity(organisationId, projectLines.get(0).getExternalProjectId(), resolvedProjectIds);
            if (projectE.isLeft()) {
                return Either.left(projectE.getLeft());
            }
            ProjectEntity project = projectE.get();

            Either<ProblemDetail, List<EventMilestoneAllocationRequest>> milestonesE = buildMilestoneAllocations(project, projectLines);
            if (milestonesE.isLeft()) {
                return Either.left(milestonesE.getLeft());
            }

            attachAllocation(project, milestonesE.get(), rootsByExternalId, rootDirectMilestones, subAllocationsByRoot);
        }

        return Either.right(buildAllocationRequests(rootsByExternalId, rootDirectMilestones, subAllocationsByRoot));
    }

    /** Validates and builds the milestone allocations for one project's rows within an event group. */
    private Either<ProblemDetail, List<EventMilestoneAllocationRequest>> buildMilestoneAllocations(ProjectEntity project,
            List<EventCsvLine> projectLines) {

        List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();
        for (EventCsvLine line : projectLines) {
            if (isBlank(line.getExternalMilestoneId())) {
                return Either.left(Problems.badRequest("External Milestone ID is required for every allocation row",
                        ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED));
            }
            // Validation only — the milestone must already exist, this file never creates one.
            if (milestoneService.findByProjectIdAndExternalMilestoneId(project.getId(), line.getExternalMilestoneId()).isEmpty()) {
                return Either.left(Problems.milestoneNotFound(line.getExternalMilestoneId()));
            }
            Either<ProblemDetail, BigDecimal> allocatedAmount = parseDecimal(line.getAllocatedAmount(), "Allocated Amount");
            if (allocatedAmount.isLeft()) return Either.left(allocatedAmount.getLeft());
            if (allocatedAmount.get() == null) {
                return Either.left(Problems.badRequest("Allocated Amount is required", ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED));
            }

            milestones.add(EventMilestoneAllocationRequest.builder()
                    .milestone(MilestoneCreateRequest.builder()
                            .externalMilestoneId(line.getExternalMilestoneId())
                            .build())
                    .allocatedAmount(allocatedAmount.get())
                    .build());
        }
        return Either.right(milestones);
    }

    /** Records {@code project}'s allocation as a root-level entry, or nests it under its root's sub-projects. */
    private void attachAllocation(ProjectEntity project, List<EventMilestoneAllocationRequest> milestones,
            Map<String, ProjectEntity> rootsByExternalId,
            Map<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones,
            Map<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot) {

        ProjectEntity parentProject = project.getParentProject();
        if (parentProject == null) {
            rootsByExternalId.put(project.getExternalProjectId(), project);
            rootDirectMilestones.put(project.getExternalProjectId(), milestones);
            return;
        }
        // `parentProject` is a lazy association — reading only its id is safe on a detached entity
        // (the id is already loaded via the FK column); a fresh findById avoids triggering lazy
        // initialization when the parent's own fields (externalProjectId) are needed.
        String parentId = Objects.requireNonNull(parentProject.getId(), "parentProject id must not be null");
        ProjectEntity root = projectRepository.findById(parentId).orElseThrow();
        rootsByExternalId.putIfAbsent(root.getExternalProjectId(), root);
        subAllocationsByRoot.computeIfAbsent(root.getExternalProjectId(), k -> new ArrayList<>())
                .add(EventSubProjectAllocationRequest.builder()
                        .externalProjectId(project.getExternalProjectId())
                        .milestones(milestones)
                        .build());
    }

    private List<EventProjectAllocationRequest> buildAllocationRequests(
            Map<String, ProjectEntity> rootsByExternalId,
            Map<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones,
            Map<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot) {

        List<EventProjectAllocationRequest> allocations = new ArrayList<>();
        for (String rootExternalId : rootsByExternalId.keySet()) {
            EventProjectAllocationRequest.EventProjectAllocationRequestBuilder allocation =
                    EventProjectAllocationRequest.builder().externalProjectId(rootExternalId);
            if (rootDirectMilestones.containsKey(rootExternalId)) {
                allocation.milestones(rootDirectMilestones.get(rootExternalId));
            }
            if (subAllocationsByRoot.containsKey(rootExternalId)) {
                allocation.subProjects(subAllocationsByRoot.get(rootExternalId));
            }
            allocations.add(allocation.build());
        }
        return allocations;
    }

    private SpendingEventCreateRequest buildSpendingEventRequest(String organisationId, EventHeader header,
            List<EventProjectAllocationRequest> allocations) {
        return SpendingEventCreateRequest.builder()
                .organisationId(organisationId)
                .eventType(header.eventType())
                .fundingId(header.fundingId())
                .fundingHash(header.fundingHash())
                .fundingEntity(header.fundingEntity())
                .currencyRcy(header.currencyRcy())
                .eventDate(header.eventDate())
                .category(header.category())
                .vendor(header.vendor())
                .amountFcy(header.amountFcy())
                .currencyFcy(header.currencyFcy())
                .fxRate(header.fxRate())
                .amountRcy(header.amountRcy())
                .hash(header.hash())
                .notes(header.notes())
                .allocations(allocations)
                .build();
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static <T> LinkedHashMap<String, List<Integer>> groupByKey(List<T> lines, Function<T, String> keyFn) {
        LinkedHashMap<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            groups.computeIfAbsent(keyFn.apply(lines.get(i)), k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    /**
     * Resolves a CSV externalProjectId reference to an internal project id — first against projects
     * created/updated earlier in this same import call, then against the database. Returns an error
     * when no project matches, or when more than one project in the organisation shares that external
     * id (the entity model does not enforce global uniqueness of externalProjectId for sub-projects
     * under different parents, only for root-scope creation). Never creates anything.
     */
    private Either<ProblemDetail, String> resolveProjectId(String organisationId, String externalProjectId, Map<String, String> resolvedProjectIds) {
        String cached = resolvedProjectIds.get(externalProjectId);
        if (cached != null) {
            return Either.right(cached);
        }
        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProject(organisationId, externalProjectId);
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        return existingE.get()
                .map(project -> {
                    resolvedProjectIds.put(externalProjectId, project.getId());
                    return Either.<ProblemDetail, String>right(project.getId());
                })
                .orElseGet(() -> Either.left(Problems.projectReferenceNotFound(externalProjectId)));
    }

    /** Same resolution as {@link #resolveProjectId}, but returns the full entity — needed to inspect {@code parentProject}. */
    private Either<ProblemDetail, ProjectEntity> resolveExistingProjectEntity(String organisationId, String externalProjectId, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProject(organisationId, externalProjectId);
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        return existingE.get()
                .map(project -> {
                    resolvedProjectIds.put(externalProjectId, project.getId());
                    return Either.<ProblemDetail, ProjectEntity>right(project);
                })
                .orElseGet(() -> Either.left(Problems.projectReferenceNotFound(externalProjectId)));
    }

    /** Looks up a project by external id without erroring on "not found" — the caller decides what a missing project means. */
    private Either<ProblemDetail, Optional<ProjectEntity>> findExistingProject(String organisationId, String externalProjectId) {
        List<ProjectEntity> matches = projectRepository.findByOrganisationIdAndExternalProjectId(organisationId, externalProjectId);
        if (matches.size() > 1) {
            return Either.left(Problems.ambiguousProjectReference(externalProjectId));
        }
        return Either.right(matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0)));
    }

    private static FundingFileImportResult fileLevelError(MultipartFile file, FundingCsvFileType type, ProblemDetail problem) {
        return FundingFileImportResult.builder()
                .fileName(file.getOriginalFilename())
                .fileType(type)
                .rowsSucceeded(0)
                .rowErrors(List.of(FundingRowError.builder().rowNumber(0).reason(problem.getDetail()).build()))
                .build();
    }

    private static FundingRowError rowError(int rowNumber, ProblemDetail problem) {
        return FundingRowError.builder().rowNumber(rowNumber).reason(problem.getDetail()).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Either<ProblemDetail, BigDecimal> parseDecimal(String raw, String fieldLabel) {
        if (isBlank(raw)) {
            return Either.right(null);
        }
        try {
            return Either.right(new BigDecimal(raw.trim()));
        } catch (NumberFormatException e) {
            return Either.left(Problems.badRequest(
                    "Invalid number for %s: %s".formatted(fieldLabel, raw), ErrorTitleConstants.CSV_ROW_INVALID));
        }
    }

    private static Either<ProblemDetail, LocalDate> parseDate(String raw, String fieldLabel) {
        try {
            return Either.right(LocalDate.parse(raw.trim()));
        } catch (DateTimeParseException e) {
            return Either.left(Problems.badRequest(
                    "Invalid date for %s: %s (expected yyyy-MM-dd)".formatted(fieldLabel, raw), ErrorTitleConstants.CSV_ROW_INVALID));
        }
    }

    private static Either<ProblemDetail, LocalDate> parseNullableDate(String raw, String fieldLabel) {
        if (isBlank(raw)) {
            return Either.right(null);
        }
        return parseDate(raw, fieldLabel);
    }

    private record FileWithType(MultipartFile file, Optional<FundingCsvFileType> type) {
    }

    private record UpsertOutcome<T>(T entity, boolean created) {
    }

    private record ProjectsFileOutcome(FundingFileImportResult fileResult, int projectsCreated, int subProjectsCreated,
            int projectsUpdated, int subProjectsUpdated) {
    }

    /** {@code orphanRoot} is true when the root was newly created by this call but every sub-project
     * row that tried to attach to it failed — signals {@link FundingProjectGroupTransactionRunner}
     * to roll the whole group's transaction back instead of leaving a childless root behind. */
    record ProjectGroupOutcome(List<FundingRowError> errors, int succeeded, int projectsCreated,
            int projectsUpdated, int subProjectsCreated, int subProjectsUpdated, boolean orphanRoot) {
    }

    private record MilestonesFileOutcome(FundingFileImportResult fileResult, int milestonesCreated, int milestonesUpdated) {
    }

    private record EventsFileOutcome(FundingFileImportResult fileResult, int eventsCreated, int allocationsCreated) {
    }

    private record EventGroupOutcome(FundingRowError error, int allocationsCreated) {
    }

    /** The event-level (non-allocation) columns shared by every row in a grouped Events-file event. */
    private record EventHeader(EventType eventType, String fundingId, String fundingHash, String fundingEntity,
            String currencyRcy, LocalDate eventDate, String category, String vendor, BigDecimal amountFcy,
            String currencyFcy, BigDecimal fxRate, BigDecimal amountRcy, String hash, String notes) {
    }

    /** Accumulates per-file outcome counts across the whole import call. */
    private static final class FundingTotals {
        private int projectsCreated;
        private int subProjectsCreated;
        private int milestonesCreated;
        private int eventsCreated;
        private int allocationsCreated;
        private int projectsUpdated;
        private int subProjectsUpdated;
        private int milestonesUpdated;

        void addProjects(ProjectsFileOutcome outcome) {
            projectsCreated += outcome.projectsCreated();
            subProjectsCreated += outcome.subProjectsCreated();
            projectsUpdated += outcome.projectsUpdated();
            subProjectsUpdated += outcome.subProjectsUpdated();
        }

        void addMilestones(MilestonesFileOutcome outcome) {
            milestonesCreated += outcome.milestonesCreated();
            milestonesUpdated += outcome.milestonesUpdated();
        }

        void addEvents(EventsFileOutcome outcome) {
            eventsCreated += outcome.eventsCreated();
            allocationsCreated += outcome.allocationsCreated();
        }

        FundingBulkImportResult toResult(boolean dryRun, List<FundingFileImportResult> fileResults) {
            return FundingBulkImportResult.builder()
                    .dryRun(dryRun)
                    .files(fileResults)
                    .projectsCreated(projectsCreated)
                    .subProjectsCreated(subProjectsCreated)
                    .milestonesCreated(milestonesCreated)
                    .eventsCreated(eventsCreated)
                    .allocationsCreated(allocationsCreated)
                    .projectsUpdated(projectsUpdated)
                    .subProjectsUpdated(subProjectsUpdated)
                    .milestonesUpdated(milestonesUpdated)
                    .build();
        }
    }

}
