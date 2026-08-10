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
 *   <li>Projects+Milestones file: a root project's columns ({@code Project Title}, {@code Funding ID},
 *   {@code Total Amount}, {@code Currency}) and, optionally on the same row, one sub-project's columns
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
 * <p><b>Partial-save semantics:</b> this orchestrator is deliberately <em>not</em> {@code @Transactional}.
 * Each call into {@code ProjectService}/{@code ProjectStructureService}/{@code MilestoneService}/
 * {@code SpendingEventService} is a call to another Spring-proxied bean, each already
 * {@code @Transactional} on its own — so every project, milestone, and event group commits (or rolls
 * back) independently. A bad row anywhere never undoes rows that already succeeded.
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
            Optional<FundingCsvFileType> maybeType = f.type();
            if (maybeType.isEmpty()) {
                fileResults.add(unrecognizedFileResult(f.file()));
                continue;
            }
            switch (maybeType.get()) {
                case PROJECTS_MILESTONES -> {
                    ProjectsMilestonesFileOutcome outcome = processProjectsMilestonesFile(organisationId, f.file(), resolvedProjectIds);
                    fileResults.add(outcome.fileResult());
                    totals.addProjectsMilestones(outcome);
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
            ProjectMilestoneGroupOutcome outcome = processProjectMilestoneGroup(organisationId, lines, idxs, resolvedProjectIds);
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
            ProjectMilestoneCsvLine line = lines.get(idx);
            ProjectEntity target = root;

            if (line.hasSubProject()) {
                Either<ProblemDetail, UpsertOutcome<ProjectEntity>> subE = upsertSubProject(root, line);
                if (subE.isLeft()) {
                    errors.add(rowError(idx + 1, subE.getLeft()));
                    continue; // nothing to attach a milestone to on this row
                }
                target = subE.get().entity();
                resolvedProjectIds.put(line.getSubProjectTitle(), target.getId());
                succeeded++;
                if (subE.get().created()) projectsCreated++; else projectsUpdated++;
            }

            if (line.hasMilestone()) {
                Either<ProblemDetail, Boolean> msE = upsertMilestoneRow(target, line);
                if (msE.isLeft()) {
                    errors.add(rowError(idx + 1, msE.getLeft()));
                    continue;
                }
                succeeded++;
                if (msE.get()) milestonesCreated++; else milestonesUpdated++;
            }
        }

        return new ProjectMilestoneGroupOutcome(errors, succeeded, projectsCreated, projectsUpdated, milestonesCreated, milestonesUpdated);
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
                .fundingId(blankToNull(rootLine.getFundingId()))
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
            return updateProjectEntity(existing.get(), line.getSubCurrency(), subAmountE.get());
        }
        // CREATE — full data is required.
        if (subAmountE.get() == null) {
            return Either.left(Problems.badRequest(
                    "Sub Total Amount is required to create sub-project: " + line.getSubProjectTitle(), ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        if (isBlank(line.getSubCurrency())) {
            return Either.left(Problems.badRequest(
                    "Sub Currency is required to create sub-project: " + line.getSubProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Either<ProblemDetail, ProjectEntity> created = projectStructureService.createSubProject(
                root, line.getSubProjectTitle(), blankToNull(line.getSubFundingId()), subAmountE.get(), line.getSubCurrency());
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
        int succeeded = 0;
        int eventsCreated = 0;
        int eventsUpdated = 0;
        int allocationsCreated = 0;
        int allocationsUpdated = 0;

        for (List<Integer> idxs : groups.values()) {
            List<EventCsvLine> group = idxs.stream().map(lines::get).toList();
            EventGroupOutcome outcome = processEventGroup(organisationId, idxs.get(0) + 1, group, resolvedProjectIds);
            if (outcome.error() != null) {
                errors.add(outcome.error());
                continue;
            }
            succeeded++;
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
                        .build(),
                eventsCreated, eventsUpdated, allocationsCreated, allocationsUpdated);
    }

    private EventGroupOutcome processEventGroup(String organisationId, int firstRowNumber, List<EventCsvLine> group,
            Map<String, String> resolvedProjectIds) {

        Either<ProblemDetail, SpendingEventCreateRequest> built = buildEventRequest(organisationId, group, resolvedProjectIds);
        if (built.isLeft()) {
            return new EventGroupOutcome(rowError(firstRowNumber, built.getLeft()), 0, 0, false);
        }
        SpendingEventCreateRequest request = built.get();

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
            return new EventGroupOutcome(rowError(firstRowNumber, error.get()), 0, 0, false);
        }
        // An update replaces the event's allocations wholesale, so every allocation row in the group
        // counts as updated rather than created when the event already existed.
        return alreadyExists
                ? new EventGroupOutcome(null, 0, group.size(), false)
                : new EventGroupOutcome(null, group.size(), 0, true);
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
            if (isBlank(line.getProjectTitle())) {
                return Either.left(Problems.badRequest("Project Title is required for every allocation row",
                        ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
            }
            byProject.computeIfAbsent(line.getProjectTitle(), k -> new ArrayList<>()).add(line);
        }
        return Either.right(byProject);
    }

    /**
     * Resolves each referenced project and builds its milestone allocations, nesting sub-project
     * references under their root — the underlying event-creation logic only resolves a flat
     * {@code projectTitle} as a ROOT project, so a sub-project must be attached via its root's
     * {@code subProjects} instead of appearing as its own top-level allocation.
     */
    private Either<ProblemDetail, List<EventProjectAllocationRequest>> resolveAllocations(String organisationId,
            Map<String, List<EventCsvLine>> byProject, Map<String, String> resolvedProjectIds) {

        LinkedHashMap<String, ProjectEntity> rootsByTitle = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventMilestoneAllocationRequest>> rootDirectMilestones = new LinkedHashMap<>();
        LinkedHashMap<String, List<EventSubProjectAllocationRequest>> subAllocationsByRoot = new LinkedHashMap<>();

        for (List<EventCsvLine> projectLines : byProject.values()) {
            // Validation only — the project must already exist, this file never creates one.
            Either<ProblemDetail, ProjectEntity> projectE =
                    resolveExistingProjectEntity(organisationId, projectLines.get(0).getProjectTitle(), resolvedProjectIds);
            if (projectE.isLeft()) {
                return Either.left(projectE.getLeft());
            }
            ProjectEntity project = projectE.get();

            Either<ProblemDetail, List<EventMilestoneAllocationRequest>> milestonesE = buildMilestoneAllocations(project, projectLines);
            if (milestonesE.isLeft()) {
                return Either.left(milestonesE.getLeft());
            }

            attachAllocation(project, milestonesE.get(), rootsByTitle, rootDirectMilestones, subAllocationsByRoot);
        }

        return Either.right(buildAllocationRequests(rootsByTitle, rootDirectMilestones, subAllocationsByRoot));
    }

    /** Validates and builds the milestone allocations for one project's rows within an event group. */
    private Either<ProblemDetail, List<EventMilestoneAllocationRequest>> buildMilestoneAllocations(ProjectEntity project,
            List<EventCsvLine> projectLines) {

        List<EventMilestoneAllocationRequest> milestones = new ArrayList<>();
        for (EventCsvLine line : projectLines) {
            if (isBlank(line.getMilestoneTitle())) {
                return Either.left(Problems.badRequest("Milestone Title is required for every allocation row",
                        ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED));
            }
            // Validation only — the milestone must already exist, this file never creates one.
            if (milestoneService.findByProjectIdAndMilestoneTitle(project.getId(), line.getMilestoneTitle()).isEmpty()) {
                return Either.left(Problems.milestoneNotFound(line.getMilestoneTitle()));
            }
            Either<ProblemDetail, BigDecimal> allocatedAmount = parseDecimal(line.getAllocatedAmount(), "Allocated Amount");
            if (allocatedAmount.isLeft()) return Either.left(allocatedAmount.getLeft());
            if (allocatedAmount.get() == null) {
                return Either.left(Problems.badRequest("Allocated Amount is required", ErrorTitleConstants.ALLOCATION_AMOUNT_REQUIRED));
            }

            milestones.add(EventMilestoneAllocationRequest.builder()
                    .milestone(MilestoneCreateRequest.builder()
                            .milestoneTitle(line.getMilestoneTitle())
                            .build())
                    .allocatedAmount(allocatedAmount.get())
                    .build());
        }
        return Either.right(milestones);
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
     * Resolves a CSV projectTitle reference (Events file) to its project entity — first against
     * projects created/updated earlier in this same import call (cache write-through, not read —
     * every call still confirms against the database), then against the database. Returns an error
     * when no project matches, or when more than one project in the organisation shares that title
     * (a bare title is only guaranteed unique within its sibling scope, not organisation-wide). Never
     * creates anything.
     */
    private Either<ProblemDetail, ProjectEntity> resolveExistingProjectEntity(String organisationId, String projectTitle, Map<String, String> resolvedProjectIds) {
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

    private record EventsFileOutcome(FundingFileImportResult fileResult, int eventsCreated, int eventsUpdated,
            int allocationsCreated, int allocationsUpdated) {
    }

    /** {@code created} is meaningless when {@code error} is set. */
    private record EventGroupOutcome(FundingRowError error, int allocationsCreated, int allocationsUpdated, boolean created) {
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
