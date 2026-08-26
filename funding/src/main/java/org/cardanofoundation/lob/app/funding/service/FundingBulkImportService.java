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
import java.util.Set;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.csv.EventCsvLine;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvFileType;
import org.cardanofoundation.lob.app.funding.domain.csv.FundingCsvTypeDetector;
import org.cardanofoundation.lob.app.funding.domain.csv.ProjectMilestoneCsvLine;
import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
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
import org.cardanofoundation.lob.app.funding.domain.view.EventMilestoneAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.EventProjectAllocationView;
import org.cardanofoundation.lob.app.funding.domain.view.FundingBulkImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingFileImportResult;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowError;
import org.cardanofoundation.lob.app.funding.domain.view.FundingRowWarning;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

/**
 * Bulk CSV import for the funding module. Accepts up to two files (Projects+Milestones, Events —
 * type auto-detected from headers, any subset, any count) and translates each row/group into the
 * existing JSON-API request DTOs, then calls the existing, already-validated service methods — this
 * class does not reimplement any funding business rule, it only parses CSV rows into those requests.
 *
 * <p>Every reference in these files is by <b>title</b>, not by any external/user-defined id — project
 * identity is (parent scope, projectTitle), milestone identity is (project, milestoneTitle), matching
 * the JSON API. A bare title is not necessarily unique across the whole organisation (only within its
 * sibling scope); the Projects+Milestones file always knows a project's exact scope from the row itself
 * (root, or the specific parent named on that row), so its lookups are exact. Only the Events file
 * references a project purely by title with no other context, so it searches broadly and fails with an
 * "ambiguous reference" error if more than one project shares that title — see
 * {@link #findExistingProjectByTitle}.
 *
 * <p><b>Upsert semantics</b> (so re-uploading a file with one changed row is safe):
 * <ul>
 *   <li>Projects+Milestones file: a root project's columns ({@code Project Title}, {@code Total Amount},
 *   {@code Currency}) and, optionally on the same row, one sub-project's columns
 *   ({@code Sub *}) and/or one milestone's columns ({@code Milestone *}) — the milestone belongs to the
 *   sub-project when one is present on the row, otherwise to the root. Consecutive rows sharing the same
 *   root {@code Project Title} are grouped into one project; the root is resolved/created once per group
 *   (from whichever row in the group actually carries its columns — not necessarily the first), and each
 *   row's sub-project and milestone are then resolved independently of the other rows, so one bad row
 *   doesn't block its siblings. This is deliberately row-order-independent within a group: a sub-project
 *   or milestone is always created directly from the data on its own row, never by looking up another
 *   row's title, so shuffling rows around never breaks the import.</li>
 *   <li>Events file: pure validation, no creation. Both {@code Project Title} and
 *   {@code Milestone Title} must already exist — this file carries only allocation amounts, not
 *   enough data to create a project or milestone from scratch.</li>
 * </ul>
 *
 * <p><b>Partial-save semantics:</b> this orchestrator is deliberately <em>not</em> {@code @Transactional}
 * as a whole. The unit of atomicity is one <em>group</em> — one root {@code Project Title} and
 * everything upserted under it (its sub-projects and milestones) for the Projects+Milestones file, or
 * one event and its allocations for the Events file — not the whole file or request. Each group commits
 * or rolls back independently: a bad row anywhere in a group undoes every write that group itself made
 * (see {@link #processGroupAtomically}), but never touches a different group that already succeeded,
 * earlier in the same file or request. An Events-file group is already atomic for free — it validates
 * and builds its whole request before making the single, already-{@code @Transactional}
 * {@code SpendingEventService.createEvent}/{@code updateEvent} call — so only the Projects+Milestones
 * file needs the explicit wrapping in {@link #processGroupAtomically}.
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

    private final CsvParser<ProjectMilestoneCsvLine> projectMilestoneCsvParser;
    private final CsvParser<EventCsvLine> eventCsvParser;
    private final FundingCsvTypeDetector csvTypeDetector;
    private final FundingProjectRepository projectRepository;
    private final ProjectService projectService;
    private final ProjectStructureService projectStructureService;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final FundingBulkImportTransactionRunner transactionRunner;

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
        // Resolved projectTitle -> internal project id, shared across both files in this call so
        // Events can see projects created/updated earlier in the same request.
        Map<String, String> resolvedProjectIds = new HashMap<>();

        List<FileWithType> filesInOrder = request.getFiles().stream()
                .map(file -> new FileWithType(file, csvTypeDetector.detect(file)))
                .sorted(Comparator.comparing(f -> f.type().map(Enum::ordinal).orElse(Integer.MAX_VALUE)))
                .toList();

        List<FundingFileImportResult> fileResults = new ArrayList<>();
        FundingTotals totals = new FundingTotals();

        for (FileWithType f : filesInOrder) {
            fileResults.add(processFile(organisationId, f, resolvedProjectIds, totals));
        }

        return totals.toResult(request.isDryRun(), fileResults);
    }

    private FundingFileImportResult processFile(String organisationId,
                                                  FileWithType f,
                                                  Map<String, String> resolvedProjectIds,
                                                  FundingTotals totals) {
        Optional<FundingCsvFileType> maybeType = f.type();
        if (maybeType.isEmpty()) {
            return unrecognizedFileResult(f.file());
        }
        FundingCsvFileType type = maybeType.get();
        Set<String> missingHeaders = csvTypeDetector.missingHeaders(f.file(), type);
        if (!missingHeaders.isEmpty()) {
            return missingHeadersResult(f.file(), type, missingHeaders);
        }
        return switch (type) {
            case PROJECTS_MILESTONES -> {
                ProjectsMilestonesFileOutcome outcome = processProjectsMilestonesFile(organisationId, f.file(), resolvedProjectIds);
                totals.addProjectsMilestones(outcome);
                yield outcome.fileResult();
            }
            case EVENTS -> {
                EventsFileOutcome outcome = processEventsFile(organisationId, f.file(), resolvedProjectIds);
                totals.addEvents(outcome);
                yield outcome.fileResult();
            }
        };
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

    /**
     * A column entirely missing from the header row parses silently as null on every row (opencsv
     * binds by header name, not position), which can otherwise surface much later as a confusing,
     * unrelated business-rule error instead of pointing at the real cause. Caught here, once the file's
     * type is already resolved, so the message can name exactly which column(s) the template requires
     * — the value in that column may still be blank, only the header itself is mandatory.
     */
    private static FundingFileImportResult missingHeadersResult(MultipartFile file, FundingCsvFileType type, Set<String> missingHeaders) {
        return FundingFileImportResult.builder()
                .fileName(file.getOriginalFilename())
                .fileType(type)
                .rowsSucceeded(0)
                .rowErrors(List.of(FundingRowError.builder()
                        .rowNumber(0)
                        .reason("CSV header is missing column(s) required by the %s template: %s".formatted(
                                type, String.join(", ", missingHeaders)))
                        .build()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Projects+Milestones file — upsert: create/update the root project once per group, then upsert
    // each row's sub-project and/or milestone independently.
    // -------------------------------------------------------------------------

    private ProjectsMilestonesFileOutcome processProjectsMilestonesFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<ProjectMilestoneCsvLine>> parsed = projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class);
        if (parsed.isLeft()) {
            return new ProjectsMilestonesFileOutcome(fileLevelError(file, FundingCsvFileType.PROJECTS_MILESTONES, parsed.getLeft()), 0, 0, 0, 0);
        }

        List<ProjectMilestoneCsvLine> lines = parsed.get();
        // Group consecutive/duplicate rows sharing the same root Project Title — the root is upserted
        // once per group, and each grouped row's sub-project and/or milestone columns (if any) are
        // upserted independently of one another.
        LinkedHashMap<String, List<Integer>> groups = groupByKey(lines, ProjectMilestoneCsvLine::getProjectTitle);

        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 0;
        int projectsCreated = 0;
        int projectsUpdated = 0;
        int milestonesCreated = 0;
        int milestonesUpdated = 0;

        for (List<Integer> idxs : groups.values()) {
            ProjectMilestoneGroupOutcome outcome = processGroupAtomically(organisationId, lines, idxs, resolvedProjectIds);
            errors.addAll(outcome.errors());
            succeeded += outcome.succeeded();
            projectsCreated += outcome.projectsCreated();
            projectsUpdated += outcome.projectsUpdated();
            milestonesCreated += outcome.milestonesCreated();
            milestonesUpdated += outcome.milestonesUpdated();
        }

        return new ProjectsMilestonesFileOutcome(
                FundingFileImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .fileType(FundingCsvFileType.PROJECTS_MILESTONES)
                        .rowsSucceeded(succeeded)
                        .rowErrors(errors)
                        .build(),
                projectsCreated, projectsUpdated, milestonesCreated, milestonesUpdated);
    }

    /**
     * Runs one root-project group so that a row error anywhere in it rolls back every write the group
     * itself made — the root project, and any sub-project or milestone from an earlier row in the same
     * group — instead of leaving a partially-built project tree behind (e.g. a root project and its
     * first milestone persisted while a later sibling sub-project fails the "sub-projects total exceeds
     * parent total" check). This is per-group atomicity, not per-file: a different group (a different
     * root {@code Project Title}) that already succeeded, earlier in this same file/request, is
     * unaffected by a later group's failure — see the class-level Javadoc.
     *
     * <p>Only wraps the group in its own transaction for a real (non dry-run) import. A dry run already
     * runs the whole request inside {@link FundingBulkImportTransactionRunner#runAndRollBack}'s one
     * transaction, unconditionally rolled back at the end regardless of any group's outcome — see
     * {@link FundingBulkImportTransactionRunner#runGroupAndRollBackOnFailure} for why nesting another
     * transactional call there would be actively harmful (risks {@code UnexpectedRollbackException}).
     *
     * <p>When the group has any row error, its create/update counts are zeroed out before being
     * returned — those operations were rolled back along with everything else in the group, so
     * reporting them as if they'd persisted (the raw counts {@link #processProjectMilestoneGroup}
     * returns, which reflect what was attempted before the failure, not what survived it) would mislead
     * the caller into thinking part of the group is safely in the database when none of it is. Each row
     * error's own message is also annotated with which project's group got rolled back because of it —
     * without that, a message like "Sub-projects total ... exceeds the parent project total ..." on row
     * 3 gives no hint that row 2's already-succeeded sub-project and milestone were undone too.
     */
    private ProjectMilestoneGroupOutcome processGroupAtomically(String organisationId, List<ProjectMilestoneCsvLine> lines,
            List<Integer> idxs, Map<String, String> resolvedProjectIds) {
        ProjectMilestoneGroupOutcome outcome = TransactionSynchronizationManager.isActualTransactionActive()
                ? processProjectMilestoneGroup(organisationId, lines, idxs, resolvedProjectIds)
                : transactionRunner.runGroupAndRollBackOnFailure(
                        () -> processProjectMilestoneGroup(organisationId, lines, idxs, resolvedProjectIds),
                        o -> !o.errors().isEmpty());
        if (!outcome.errors().isEmpty()) {
            String rootProjectTitle = lines.get(idxs.get(0)).getProjectTitle();
            List<FundingRowError> annotatedErrors = outcome.errors().stream()
                    .map(error -> withRollbackNote(error, rootProjectTitle))
                    .toList();
            return new ProjectMilestoneGroupOutcome(annotatedErrors, 0, 0, 0, 0, 0);
        }
        return outcome;
    }

    private static FundingRowError withRollbackNote(FundingRowError error, String rootProjectTitle) {
        return FundingRowError.builder()
                .rowNumber(error.getRowNumber())
                .reason(error.getReason() + ". All changes for project \"" + rootProjectTitle
                        + "\" in this request were rolled back because of this error.")
                .title(error.getTitle())
                .build();
    }

    /**
     * Upserts the group's root project once, then walks every row in the group upserting its
     * sub-project (if any) and milestone (if any) independently of the other rows — a bad sub-project
     * or milestone row is reported and skipped without affecting its siblings. If the root itself
     * fails to resolve/create, every row in the group reports the same error (there is nothing to
     * attach a sub-project or milestone to).
     */
    private ProjectMilestoneGroupOutcome processProjectMilestoneGroup(String organisationId, List<ProjectMilestoneCsvLine> lines,
            List<Integer> idxs, Map<String, String> resolvedProjectIds) {

        if (isBlank(lines.get(idxs.get(0)).getProjectTitle())) {
            return groupFailure(idxs, Problems.badRequest("Project Title is required", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        // The root's own columns may be on any row of the group, not necessarily the first — e.g. a
        // sub-project row could come first if the file isn't ordered "root row before its subs".
        ProjectMilestoneCsvLine rootLine = idxs.stream().map(lines::get)
                .filter(ProjectMilestoneCsvLine::hasRootProjectData)
                .findFirst()
                .orElseGet(() -> lines.get(idxs.get(0)));

        Either<ProblemDetail, UpsertOutcome<ProjectEntity>> rootE = upsertRootProject(organisationId, rootLine);
        if (rootE.isLeft()) {
            return groupFailure(idxs, rootE.getLeft());
        }
        ProjectEntity root = rootE.get().entity();
        resolvedProjectIds.put(rootLine.getProjectTitle(), root.getId());

        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 1; // the root
        int projectsCreated = rootE.get().created() ? 1 : 0;
        int projectsUpdated = rootE.get().created() ? 0 : 1;
        int milestonesCreated = 0;
        int milestonesUpdated = 0;

        for (int idx : idxs) {
            RowOutcome row = processGroupRow(root, idx, lines.get(idx), resolvedProjectIds);
            if (row.error() != null) {
                errors.add(row.error());
            }
            succeeded += row.succeeded();
            projectsCreated += row.projectsCreated();
            projectsUpdated += row.projectsUpdated();
            milestonesCreated += row.milestonesCreated();
            milestonesUpdated += row.milestonesUpdated();
        }

        return new ProjectMilestoneGroupOutcome(errors, succeeded, projectsCreated, projectsUpdated, milestonesCreated, milestonesUpdated);
    }

    /**
     * Upserts one row's sub-project (if any) and milestone (if any). A failed sub-project upsert
     * reports its error and skips the milestone — there is nothing to attach it to on this row.
     *
     * <p>A row whose {@code Sub Total Amount} (or {@code Milestone Amount}/{@code Milestone Date})
     * is filled in but whose matching title is missing — see {@link
     * ProjectMilestoneCsvLine#hasOrphanedSubProjectData()} / {@link
     * ProjectMilestoneCsvLine#hasOrphanedMilestoneData()} — is reported as a row error instead of
     * being silently dropped or (for a sub-project amount with no sub-project title) attached to
     * the wrong project.
     */
    private RowOutcome processGroupRow(ProjectEntity root, int idx, ProjectMilestoneCsvLine line, Map<String, String> resolvedProjectIds) {
        ProjectEntity target = root;
        int succeeded = 0;
        int projectsCreated = 0;
        int projectsUpdated = 0;

        if (line.hasOrphanedSubProjectData()) {
            return new RowOutcome(rowError(idx + 1, Problems.badRequest(
                    "Sub Project Title is required when Sub Total Amount is provided",
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED)), 0, 0, 0, 0, 0);
        }

        if (line.hasSubProject()) {
            Either<ProblemDetail, UpsertOutcome<ProjectEntity>> subE = upsertSubProject(root, line);
            if (subE.isLeft()) {
                return new RowOutcome(rowError(idx + 1, subE.getLeft()), 0, 0, 0, 0, 0);
            }
            target = subE.get().entity();
            resolvedProjectIds.put(line.getSubProjectTitle(), target.getId());
            succeeded++;
            if (subE.get().created()) projectsCreated++; else projectsUpdated++;
        }

        if (line.hasOrphanedMilestoneData()) {
            return new RowOutcome(rowError(idx + 1, Problems.badRequest(
                    "Milestone Title is required when Milestone Amount or Milestone Date is provided",
                    ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED)), succeeded, projectsCreated, projectsUpdated, 0, 0);
        }

        if (!line.hasMilestone()) {
            return new RowOutcome(null, succeeded, projectsCreated, projectsUpdated, 0, 0);
        }

        Either<ProblemDetail, Boolean> msE = upsertMilestoneRow(target, line);
        if (msE.isLeft()) {
            return new RowOutcome(rowError(idx + 1, msE.getLeft()), succeeded, projectsCreated, projectsUpdated, 0, 0);
        }
        boolean milestoneCreated = msE.get();
        return new RowOutcome(null, succeeded + 1, projectsCreated, projectsUpdated,
                milestoneCreated ? 1 : 0, milestoneCreated ? 0 : 1);
    }

    private static ProjectMilestoneGroupOutcome groupFailure(List<Integer> idxs, ProblemDetail problem) {
        List<FundingRowError> errors = idxs.stream().map(idx -> rowError(idx + 1, problem)).toList();
        return new ProjectMilestoneGroupOutcome(errors, 0, 0, 0, 0, 0);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> upsertRootProject(String organisationId, ProjectMilestoneCsvLine rootLine) {
        Optional<ProjectEntity> existing = projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(
                organisationId, rootLine.getProjectTitle());

        Either<ProblemDetail, BigDecimal> totalAmountE = parseDecimal(rootLine.getTotalAmount(), "Total Amount");
        if (totalAmountE.isLeft()) {
            return Either.left(totalAmountE.getLeft());
        }

        if (existing.isPresent()) {
            return updateProjectEntity(existing.get(), rootLine.getCurrency(), totalAmountE.get());
        }
        // CREATE — full data is required.
        if (totalAmountE.get() == null) {
            return Either.left(Problems.badRequest(
                    "Total Amount is required to create project: " + rootLine.getProjectTitle(), ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        if (isBlank(rootLine.getCurrency())) {
            return Either.left(Problems.badRequest(
                    "Currency is required to create project: " + rootLine.getProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        ProjectView view = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                .organisationId(organisationId)
                .projectTitle(rootLine.getProjectTitle())
                .totalAmount(totalAmountE.get())
                .currency(rootLine.getCurrency())
                .build());
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        ProjectEntity created = projectRepository.findById(view.getProjectId()).orElseThrow();
        return Either.right(new UpsertOutcome<>(created, true));
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> upsertSubProject(ProjectEntity root, ProjectMilestoneCsvLine line) {
        Optional<ProjectEntity> existing = projectRepository.findByParentProjectIdAndProjectTitle(root.getId(), line.getSubProjectTitle());

        Either<ProblemDetail, BigDecimal> subAmountE = parseDecimal(line.getSubTotalAmount(), "Sub Total Amount");
        if (subAmountE.isLeft()) {
            return Either.left(subAmountE.getLeft());
        }

        if (existing.isPresent()) {
            return updateProjectEntity(existing.get(), null, subAmountE.get());
        }
        // CREATE — full data is required. There is no "Sub Currency" column: a sub-project always
        // takes the root's currency (see ProjectStructureService.createSubProject).
        if (subAmountE.get() == null) {
            return Either.left(Problems.badRequest(
                    "Sub Total Amount is required to create sub-project: " + line.getSubProjectTitle(), ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        Either<ProblemDetail, ProjectEntity> created = projectStructureService.createSubProject(
                root, line.getSubProjectTitle(), null, subAmountE.get(), null);
        if (created.isLeft()) {
            return Either.left(created.getLeft());
        }
        return Either.right(new UpsertOutcome<>(created.get(), true));
    }

    /**
     * UPDATE — partial: a blank CSV cell means "leave this field unchanged", and a cell that repeats
     * the field's current stored value is treated the same way (also left out of the request) so that
     * re-uploading the same, unchanged CSV is a safe no-op — it must not re-trigger validations tied to
     * other state that can legitimately grow over time (e.g. a milestone's cumulative event
     * allocations), which would otherwise reject a value that isn't actually changing. Title is never
     * sent — it's immutable and already matched.
     */
    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> updateProjectEntity(ProjectEntity existing, String currency, BigDecimal totalAmount) {
        ProjectUpdateRequest updateRequest = ProjectUpdateRequest.builder()
                .totalAmount(ifChanged(totalAmount, existing.getTotalAmount()))
                .currency(ifChanged(blankToNull(currency), existing.getCurrency()))
                .build();
        ProjectView view = projectService.updateProject(existing.getId(), updateRequest);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        return Either.right(new UpsertOutcome<>(existing, false));
    }

    /**
     * Returns {@code Right(true)} when a new milestone was created, {@code Right(false)} when an
     * existing one was updated. A milestone's currency always matches its project's — there is no
     * separate "Milestone Currency" column — so it's taken from the already-resolved {@code project}
     * entity rather than the CSV row (which may leave the row-level {@code Currency} cell blank on a
     * continuation row that doesn't re-declare the project).
     */
    private Either<ProblemDetail, Boolean> upsertMilestoneRow(ProjectEntity project, ProjectMilestoneCsvLine line) {
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
        String currency = project.getCurrency();

        Optional<MilestoneEntity> existing = milestoneService.findByProjectIdAndMilestoneTitle(project.getId(), line.getMilestoneTitle());
        if (existing.isPresent()) {
            // UPDATE — partial: a blank (or unchanged) value means "leave this field alone" — see
            // updateProjectEntity's Javadoc for why a resent-but-unchanged value must not be forwarded:
            // this milestone's amount can be unchanged while its cumulative event allocations have
            // legitimately grown (e.g. both a FUNDING and a SPENDING event allocated against it), and
            // resending the same amount must not spuriously trip that coverage check. milestoneTitle is
            // never sent — it's immutable and we already matched the existing row by its exact title.
            MilestoneEntity current = existing.get();
            MilestoneUpdateRequest updateRequest = MilestoneUpdateRequest.builder()
                    .milestoneAmount(ifChanged(amount, current.getMilestoneAmount()))
                    .currency(ifChanged(currency, current.getCurrency()))
                    .milestoneDate(ifChanged(date, current.getMilestoneDate()))
                    .build();
            MilestoneView view = milestoneService.updateMilestone(project.getId(), current.getId(), updateRequest);
            Optional<ProblemDetail> error = view.getError();
            if (error.isPresent()) {
                return Either.left(error.get());
            }
            return Either.right(false);
        }

        // CREATE — full data is required.
        if (amount == null || date == null) {
            return Either.left(Problems.badRequest(
                    "milestoneAmount and milestoneDate are required to create milestone: " + line.getMilestoneTitle(),
                    ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED));
        }
        if (isBlank(currency)) {
            return Either.left(Problems.badRequest(
                    "Project " + project.getProjectTitle() + " has no currency, required to create milestone: " + line.getMilestoneTitle(),
                    ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle(line.getMilestoneTitle())
                .milestoneAmount(amount)
                .currency(currency)
                .milestoneDate(date)
                .build();
        MilestoneView view = milestoneService.createMilestone(project.getId(), request);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        return Either.right(true);
    }

    // -------------------------------------------------------------------------
    // Events file — upsert by the event's deterministic id; the referenced project and milestone
    // must already exist (this file never creates one).
    // -------------------------------------------------------------------------

    private EventsFileOutcome processEventsFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<EventCsvLine>> parsed = eventCsvParser.parseCsv(file, EventCsvLine.class);
        if (parsed.isLeft()) {
            return new EventsFileOutcome(fileLevelError(file, FundingCsvFileType.EVENTS, parsed.getLeft()), 0, 0, 0, 0);
        }

        List<EventCsvLine> lines = parsed.get();
        LinkedHashMap<String, List<Integer>> groups = groupByKey(lines, FundingBulkImportService::eventKey);

        List<FundingRowError> errors = new ArrayList<>();
        List<FundingRowWarning> warnings = new ArrayList<>();
        int succeeded = 0;
        int eventsCreated = 0;
        int eventsUpdated = 0;
        int allocationsCreated = 0;
        int allocationsUpdated = 0;

        for (List<Integer> idxs : groups.values()) {
            List<IndexedLine> group = idxs.stream().map(idx -> new IndexedLine(idx + 1, lines.get(idx))).toList();
            EventGroupOutcome outcome = processEventGroup(organisationId, group, resolvedProjectIds);
            if (!outcome.errors().isEmpty()) {
                errors.addAll(outcome.errors());
                continue;
            }
            succeeded++;
            warnings.addAll(outcome.warnings());
            if (outcome.created()) eventsCreated++; else eventsUpdated++;
            allocationsCreated += outcome.allocationsCreated();
            allocationsUpdated += outcome.allocationsUpdated();
        }

        return new EventsFileOutcome(
                FundingFileImportResult.builder()
                        .fileName(file.getOriginalFilename())
                        .fileType(FundingCsvFileType.EVENTS)
                        .rowsSucceeded(succeeded)
                        .rowErrors(errors)
                        .rowWarnings(warnings)
                        .build(),
                eventsCreated, eventsUpdated, allocationsCreated, allocationsUpdated);
    }

    /**
     * Builds and (if every row in the group is individually valid) persists one event. Unlike the
     * single-request JSON API — where {@link FundingValidations} and {@code SpendingEventService}
     * deliberately return only the <em>first</em> violation, since there's exactly one thing to report
     * back to the caller — a CSV event group represents several independent rows, and a user fixing
     * their file from one reported error at a time is a bad experience. So this method first validates
     * every row exhaustively (never stopping at the first bad row — see {@link
     * #groupAllocationRowsByProject} and {@link #buildMilestoneAllocations}), collecting one error per
     * bad row, and only calls {@code createEvent}/{@code updateEvent} when that pass finds nothing
     * wrong. Cross-row/event-level rules that aren't tied to a single CSV row (e.g. the event's total
     * not being fully allocated) are still left to that single call and reported as one error, since
     * they aren't "per row" in the first place.
     *
     * <p>A row that exceeds its milestone's or project's budget is no longer a validation failure — the
     * budget cap was removed (overspend is allowed and recorded like any other spend). Once the event is
     * persisted, its view carries an {@code overspend} flag per project/milestone allocation (see
     * {@code SpendingEventService#toView}); this method maps any flagged allocation back to the CSV row(s)
     * that fed it (via {@code milestoneRowNumbers}/{@code projectRowNumbers}) and reports it as a
     * non-blocking {@link FundingRowWarning} instead — it never fails the row, the group, or the import.
     */
    private EventGroupOutcome processEventGroup(String organisationId, List<IndexedLine> group,
            Map<String, String> resolvedProjectIds) {
        int firstRowNumber = group.get(0).rowNumber();

        Either<ProblemDetail, EventHeader> headerE = parseEventHeader(group.get(0).line());
        if (headerE.isLeft()) {
            return new EventGroupOutcome(List.of(rowError(firstRowNumber, headerE.getLeft())), List.of(), 0, 0, false);
        }
        EventHeader header = headerE.get();

        List<FundingRowError> errors = new ArrayList<>();
        Map<String, List<IndexedLine>> byProject = groupAllocationRowsByProject(group, errors);
        Map<String, Integer> milestoneRowNumbers = new HashMap<>();
        Map<String, Integer> projectRowNumbers = new HashMap<>();
        List<EventProjectAllocationRequest> allocations = resolveAllocations(organisationId, byProject,
                resolvedProjectIds, header.eventType(), errors, milestoneRowNumbers, projectRowNumbers);
        if (!errors.isEmpty()) {
            return new EventGroupOutcome(errors, List.of(), 0, 0, false);
        }

        SpendingEventCreateRequest request = buildSpendingEventRequest(organisationId, header, allocations);

        // Upsert: an event's id is fully deterministic from its header fields, so re-uploading the
        // same Events file resolves to the same event — update its allocations (replacing them)
        // instead of failing as "already exists". updateEvent already refuses to touch a published
        // event on its own (Problems.conflict via its internal requireDraft guard).
        String eventId = FundingEventEntity.id(organisationId, request.getEventType(), request.getFundingId(),
                request.getFundingHash(), request.getCurrencyRcy());
        boolean alreadyExists = spendingEventService.findById(eventId).isPresent();

        SpendingEventView view = alreadyExists
                ? spendingEventService.updateEvent(eventId, request)
                : spendingEventService.createEvent(request);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return new EventGroupOutcome(List.of(rowError(firstRowNumber, error.get())), List.of(), 0, 0, false);
        }
        List<FundingRowWarning> warnings = collectOverspendWarnings(view, milestoneRowNumbers, projectRowNumbers, firstRowNumber);

        // An update replaces the event's allocations wholesale, so every allocation row in the group
        // counts as updated rather than created when the event already existed.
        return alreadyExists
                ? new EventGroupOutcome(List.of(), warnings, 0, group.size(), false)
                : new EventGroupOutcome(List.of(), warnings, group.size(), 0, true);
    }

    /**
     * Maps overspend flags on the persisted event's view back to the CSV row(s) that fed each
     * allocation, one warning per flagged project or milestone. Falls back to the group's first row
     * number if a flagged allocation can't be traced to a specific row (defensive only — every
     * allocation in the view was built from a row in this group).
     */
    private List<FundingRowWarning> collectOverspendWarnings(SpendingEventView view,
            Map<String, Integer> milestoneRowNumbers, Map<String, Integer> projectRowNumbers, int fallbackRowNumber) {
        List<FundingRowWarning> warnings = new ArrayList<>();
        List<EventProjectAllocationView> projectAllocations = view.getProjectAllocations();
        if (projectAllocations == null) {
            return warnings;
        }
        for (EventProjectAllocationView pv : projectAllocations) {
            if (pv.isOverspend()) {
                int rowNumber = projectRowNumbers.getOrDefault(pv.getProjectId(), fallbackRowNumber);
                warnings.add(rowWarning(rowNumber, "Project '%s' cumulative spend %s exceeds its budget %s"
                        .formatted(pv.getProjectTitle(), pv.getSpentAmount(), pv.getTotalAmount())));
            }
            for (EventMilestoneAllocationView mv : pv.getMilestoneAllocations()) {
                if (mv.isOverspend()) {
                    int rowNumber = milestoneRowNumbers.getOrDefault(mv.getMilestoneId(), fallbackRowNumber);
                    warnings.add(rowWarning(rowNumber, "Milestone '%s' cumulative spend %s exceeds its budget %s"
                            .formatted(mv.getMilestoneTitle(), mv.getSpentAmount(), mv.getMilestoneAmount())));
                }
            }
        }
        return warnings;
    }

    private static String eventKey(EventCsvLine line) {
        return String.join("||",
                nullToEmpty(line.getFundingId()), nullToEmpty(line.getEventType()),
                nullToEmpty(line.getFundingHash()), nullToEmpty(line.getCurrencyRcy()));
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

    /**
     * Groups allocation rows by target project, preserving first-seen order, so a project that receives
     * several milestone allocations in this event appears once with all its milestones. A row with a
     * blank {@code Project Title} is reported and skipped — never bails out of the whole group, so
     * every other row still gets grouped and validated (see {@link #processEventGroup}).
     */
    private LinkedHashMap<String, List<IndexedLine>> groupAllocationRowsByProject(List<IndexedLine> group, List<FundingRowError> errors) {
        LinkedHashMap<String, List<IndexedLine>> byProject = new LinkedHashMap<>();
        for (IndexedLine il : group) {
            if (isBlank(il.line().getProjectTitle())) {
                errors.add(rowError(il.rowNumber(), Problems.badRequest("Project Title is required for every allocation row",
                        ErrorTitleConstants.PROJECT_FIELDS_REQUIRED)));
                continue;
            }
            byProject.computeIfAbsent(allocationTargetKey(il.line()), k -> new ArrayList<>()).add(il);
        }
        return byProject;
    }

    /**
     * Identifies an allocation row's target: {@code Project Title} alone, plus {@code Sub Project
     * Title} when set. Two rows only share a target when both match — so allocations to two
     * different, same-titled sub-projects under the same root (disambiguated by their own {@code Sub
     * Project Title}) never merge into one group.
     */
    private static String allocationTargetKey(EventCsvLine line) {
        return line.getProjectTitle() + "||" + nullToEmpty(line.getSubProjectTitle());
    }

    /**
     * Resolves each referenced project and builds its milestone allocations, nesting sub-project
     * references under their root — the underlying event-creation logic only resolves a flat
     * {@code projectTitle} as a ROOT project, so a sub-project must be attached via its root's
     * {@code subProjects} instead of appearing as its own top-level allocation.
     *
     * <p>Every target project is resolved and every one of its rows validated regardless of an earlier
     * project or row failing — each failure is appended to {@code errors} instead of aborting the rest
     * of the group, so one CSV import surfaces every row's problem instead of only the first. The
     * returned allocation list is only meaningful (and only used by the caller) when {@code errors}
     * ends up empty. {@code milestoneRowNumbers}/{@code projectRowNumbers} are populated as a side
     * effect (keyed by resolved milestone/project id) so a later overspend flag on the persisted event's
     * view can be traced back to the row that produced it — see {@link #collectOverspendWarnings}.
     */
    private List<EventProjectAllocationRequest> resolveAllocations(String organisationId,
            Map<String, List<IndexedLine>> byProject, Map<String, String> resolvedProjectIds,
            EventType eventType, List<FundingRowError> errors,
            Map<String, Integer> milestoneRowNumbers, Map<String, Integer> projectRowNumbers) {

        LinkedHashMap<String, ProjectEntity> rootsByTitle = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot = new LinkedHashMap<>();

        for (List<IndexedLine> projectLines : byProject.values()) {
            IndexedLine first = projectLines.get(0);
            // Validation only — the project must already exist, this file never creates one.
            Either<ProblemDetail, ProjectEntity> projectE = resolveExistingProjectEntity(
                    organisationId, first.line().getProjectTitle(), first.line().getSubProjectTitle(), resolvedProjectIds);
            if (projectE.isLeft()) {
                errors.add(rowError(first.rowNumber(), projectE.getLeft()));
                continue;
            }
            ProjectEntity project = projectE.get();
            projectRowNumbers.put(project.getId(), first.rowNumber());

            List<EventMilestoneAllocationRequest> milestones =
                    buildMilestoneAllocations(project, projectLines, eventType, errors, milestoneRowNumbers);

            attachAllocation(project, milestones, rootsByTitle, rootDirectMilestones, subAllocationsByRoot);
        }

        return buildAllocationRequests(rootsByTitle, rootDirectMilestones, subAllocationsByRoot);
    }

    /**
     * Validates and builds the milestone allocations for one project's rows within an event group. Each
     * row is validated independently — a bad row (blank title, unknown milestone, or an
     * unparsable/missing amount) is appended to {@code errors} and skipped, so a sibling row for the
     * same project is still checked instead of being left unreported (this is what let a second
     * offending milestone row on the same project silently pass import review before). A row's amount
     * is no longer capped against the milestone's budget here — {@link FundingValidations#allocation}
     * (the same rule {@code SpendingEventService} applies when it actually persists the event) only
     * checks that the amount is present and positive; overspend is a warning surfaced later from the
     * persisted event's view, not a row error.
     */
    private List<EventMilestoneAllocationRequest> buildMilestoneAllocations(ProjectEntity project,
            List<IndexedLine> projectLines, EventType eventType, List<FundingRowError> errors,
            Map<String, Integer> milestoneRowNumbers) {

        List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();
        for (IndexedLine il : projectLines) {
            buildMilestoneAllocation(project, il, eventType, errors, milestoneRowNumbers).ifPresent(milestones::add);
        }
        return milestones;
    }

    /** Validates a single allocation row, appending to {@code errors} and returning empty if it fails. */
    private Optional<EventMilestoneAllocationRequest> buildMilestoneAllocation(ProjectEntity project,
            IndexedLine il, EventType eventType, List<FundingRowError> errors, Map<String, Integer> milestoneRowNumbers) {

        EventCsvLine line = il.line();
        if (isBlank(line.getMilestoneTitle())) {
            errors.add(rowError(il.rowNumber(), Problems.badRequest("Milestone Title is required for every allocation row",
                    ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED)));
            return Optional.empty();
        }
        // Validation only — the milestone must already exist, this file never creates one.
        Optional<MilestoneEntity> milestone = milestoneService.findByProjectIdAndMilestoneTitle(project.getId(), line.getMilestoneTitle());
        if (milestone.isEmpty()) {
            errors.add(rowError(il.rowNumber(), Problems.milestoneNotFound(line.getMilestoneTitle())));
            return Optional.empty();
        }
        milestoneRowNumbers.put(milestone.get().getId(), il.rowNumber());
        Either<ProblemDetail, BigDecimal> allocatedAmountE = parseDecimal(line.getAllocatedAmount(), "Allocated Amount");
        if (allocatedAmountE.isLeft()) {
            errors.add(rowError(il.rowNumber(), allocatedAmountE.getLeft()));
            return Optional.empty();
        }
        BigDecimal allocatedAmount = allocatedAmountE.get();
        if (allocatedAmount == null) {
            errors.add(rowError(il.rowNumber(), Problems.badRequest("Allocated Amount is required", ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED)));
            return Optional.empty();
        }
        Optional<ProblemDetail> allocationProblem = FundingValidations.allocation(allocatedAmount, milestone.get(), eventType);
        if (allocationProblem.isPresent()) {
            errors.add(rowError(il.rowNumber(), allocationProblem.get()));
            return Optional.empty();
        }

        return Optional.of(EventMilestoneAllocationRequest.builder()
                .milestone(MilestoneCreateRequest.builder()
                        .milestoneTitle(line.getMilestoneTitle())
                        .build())
                .allocatedAmount(allocatedAmount)
                .build());
    }

    /** Records {@code project}'s allocation as a root-level entry, or nests it under its root's sub-projects. */
    private void attachAllocation(ProjectEntity project, List<EventMilestoneAllocationRequest> milestones,
            Map<String, ProjectEntity> rootsByTitle,
            Map<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones,
            Map<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot) {

        ProjectEntity parentProject = project.getParentProject();
        if (parentProject == null) {
            rootsByTitle.put(project.getProjectTitle(), project);
            rootDirectMilestones.put(project.getProjectTitle(), milestones);
            return;
        }
        // `parentProject` is a lazy association — reading only its id is safe on a detached entity
        // (the id is already loaded via the FK column); a fresh findById avoids triggering lazy
        // initialization when the parent's own fields (projectTitle) are needed.
        String parentId = Objects.requireNonNull(parentProject.getId(), "parentProject id must not be null");
        ProjectEntity root = projectRepository.findById(parentId).orElseThrow();
        rootsByTitle.putIfAbsent(root.getProjectTitle(), root);
        subAllocationsByRoot.computeIfAbsent(root.getProjectTitle(), k -> new ArrayList<>())
                .add(EventSubProjectAllocationRequest.builder()
                        .projectTitle(project.getProjectTitle())
                        .milestones(milestones)
                        .build());
    }

    private List<EventProjectAllocationRequest> buildAllocationRequests(
            Map<String, ProjectEntity> rootsByTitle,
            Map<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones,
            Map<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot) {

        List<EventProjectAllocationRequest> allocations = new ArrayList<>();
        for (String rootTitle : rootsByTitle.keySet()) {
            EventProjectAllocationRequest.EventProjectAllocationRequestBuilder allocation =
                    EventProjectAllocationRequest.builder().projectTitle(rootTitle);
            if (rootDirectMilestones.containsKey(rootTitle)) {
                allocation.milestones(rootDirectMilestones.get(rootTitle));
            }
            if (subAllocationsByRoot.containsKey(rootTitle)) {
                allocation.subProjects(subAllocationsByRoot.get(rootTitle));
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
     * Resolves a CSV project reference (Events file) to its project entity — never creates anything.
     * When {@code subProjectTitle} is blank, {@code projectTitle} is resolved by title alone at any
     * depth (root or sub-project), exactly as before: an error when no project matches, or when more
     * than one project in the organisation shares that title (a bare title is only guaranteed unique
     * within its sibling scope, not organisation-wide) — see {@link #findExistingProjectByTitle}.
     * When {@code subProjectTitle} is set, {@code projectTitle} is instead resolved as the <em>root</em>
     * project (unique per organisation), and {@code subProjectTitle} is resolved as that root's
     * immediate sub-project — this scoped lookup can never be ambiguous, since the parent is fixed by
     * the root lookup, so it's the way to disambiguate two same-titled sub-projects under different
     * roots.
     */
    private Either<ProblemDetail, ProjectEntity> resolveExistingProjectEntity(String organisationId, String projectTitle,
            String subProjectTitle, Map<String, String> resolvedProjectIds) {

        if (isBlank(subProjectTitle)) {
            Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProjectByTitle(organisationId, projectTitle);
            if (existingE.isLeft()) {
                return Either.left(existingE.getLeft());
            }
            return existingE.get()
                    .map(project -> {
                        resolvedProjectIds.put(projectTitle, project.getId());
                        return Either.<ProblemDetail, ProjectEntity>right(project);
                    })
                    .orElseGet(() -> Either.left(Problems.projectReferenceNotFound(projectTitle)));
        }

        Optional<ProjectEntity> root = projectRepository.findByOrganisationIdAndProjectTitleAndParentProjectIsNull(
                organisationId, projectTitle);
        if (root.isEmpty()) {
            return Either.left(Problems.projectReferenceNotFound(projectTitle));
        }
        Optional<ProjectEntity> sub = projectRepository.findByParentProjectIdAndProjectTitle(root.get().getId(), subProjectTitle);
        if (sub.isEmpty()) {
            return Either.left(Problems.subProjectReferenceNotFound(projectTitle, subProjectTitle));
        }
        resolvedProjectIds.put(subProjectTitle, sub.get().getId());
        return Either.right(sub.get());
    }

    /** Looks up a project by title (any level) without erroring on "not found" — the caller decides what a missing project means. */
    private Either<ProblemDetail, Optional<ProjectEntity>> findExistingProjectByTitle(String organisationId, String projectTitle) {
        List<ProjectEntity> matches = projectRepository.findByOrganisationIdAndProjectTitle(organisationId, projectTitle);
        if (matches.size() > 1) {
            return Either.left(Problems.ambiguousProjectReference(projectTitle));
        }
        return Either.right(matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0)));
    }

    private static FundingFileImportResult fileLevelError(MultipartFile file, FundingCsvFileType type, ProblemDetail problem) {
        return FundingFileImportResult.builder()
                .fileName(file.getOriginalFilename())
                .fileType(type)
                .rowsSucceeded(0)
                .rowErrors(List.of(FundingRowError.builder().rowNumber(0).reason(problem.getDetail()).title(problem.getTitle()).build()))
                .build();
    }

    private static FundingRowError rowError(int rowNumber, ProblemDetail problem) {
        return FundingRowError.builder().rowNumber(rowNumber).reason(problem.getDetail()).title(problem.getTitle()).build();
    }

    private static FundingRowWarning rowWarning(int rowNumber, String reason) {
        return FundingRowWarning.builder().rowNumber(rowNumber).reason(reason).build();
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

    /**
     * Returns {@code newValue} only when it's both present and different from {@code current} —
     * otherwise {@code null}, so the caller's partial-update request treats it the same as "not
     * supplied". Used to make re-uploading an unchanged CSV a safe no-op: a resent value that merely
     * repeats what's already stored must not be forwarded into an update request, or it can
     * needlessly re-trigger validations tied to other, independently-changing state (e.g. a
     * milestone's cumulative event allocations).
     */
    private static BigDecimal ifChanged(BigDecimal newValue, BigDecimal current) {
        return (newValue != null && (current == null || newValue.compareTo(current) != 0)) ? newValue : null;
    }

    private static String ifChanged(String newValue, String current) {
        return (newValue != null && !newValue.equals(current)) ? newValue : null;
    }

    private static LocalDate ifChanged(LocalDate newValue, LocalDate current) {
        return (newValue != null && !newValue.equals(current)) ? newValue : null;
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

    private record ProjectsMilestonesFileOutcome(FundingFileImportResult fileResult, int projectsCreated, int projectsUpdated,
            int milestonesCreated, int milestonesUpdated) {
    }

    private record ProjectMilestoneGroupOutcome(List<FundingRowError> errors, int succeeded, int projectsCreated,
            int projectsUpdated, int milestonesCreated, int milestonesUpdated) {
    }

    /** Outcome of upserting a single Projects+Milestones row's sub-project and/or milestone. */
    private record RowOutcome(FundingRowError error, int succeeded, int projectsCreated, int projectsUpdated,
            int milestonesCreated, int milestonesUpdated) {
    }

    private record EventsFileOutcome(FundingFileImportResult fileResult, int eventsCreated, int eventsUpdated,
            int allocationsCreated, int allocationsUpdated) {
    }

    /** {@code created} is meaningless when {@code errors} is non-empty. */
    private record EventGroupOutcome(List<FundingRowError> errors, List<FundingRowWarning> warnings,
            int allocationsCreated, int allocationsUpdated, boolean created) {
    }

    /** An Events-file CSV row paired with its 1-based row number, threaded through group processing so
     * a validation failure on any row (not just the group's first) can be reported against its own row. */
    private record IndexedLine(int rowNumber, EventCsvLine line) {
    }

    /** The event-level (non-allocation) columns shared by every row in a grouped Events-file event. */
    private record EventHeader(EventType eventType, String fundingId, String fundingHash, String fundingEntity,
            String currencyRcy, LocalDate eventDate, String category, String vendor, BigDecimal amountFcy,
            String currencyFcy, BigDecimal fxRate, BigDecimal amountRcy, String hash, String notes) {
    }

    /** Accumulates per-file outcome counts across the whole import call. */
    private static final class FundingTotals {
        private int projectsCreated;
        private int projectsUpdated;
        private int milestonesCreated;
        private int milestonesUpdated;
        private int eventsCreated;
        private int eventsUpdated;
        private int allocationsCreated;
        private int allocationsUpdated;

        void addProjectsMilestones(ProjectsMilestonesFileOutcome outcome) {
            projectsCreated += outcome.projectsCreated();
            projectsUpdated += outcome.projectsUpdated();
            milestonesCreated += outcome.milestonesCreated();
            milestonesUpdated += outcome.milestonesUpdated();
        }

        void addEvents(EventsFileOutcome outcome) {
            eventsCreated += outcome.eventsCreated();
            eventsUpdated += outcome.eventsUpdated();
            allocationsCreated += outcome.allocationsCreated();
            allocationsUpdated += outcome.allocationsUpdated();
        }

        FundingBulkImportResult toResult(boolean dryRun, List<FundingFileImportResult> fileResults) {
            return FundingBulkImportResult.builder()
                    .dryRun(dryRun)
                    .files(fileResults)
                    .projectsCreated(projectsCreated)
                    .milestonesCreated(milestonesCreated)
                    .eventsCreated(eventsCreated)
                    .eventsUpdated(eventsUpdated)
                    .allocationsCreated(allocationsCreated)
                    .allocationsUpdated(allocationsUpdated)
                    .projectsUpdated(projectsUpdated)
                    .milestonesUpdated(milestonesUpdated)
                    .build();
        }
    }

}
