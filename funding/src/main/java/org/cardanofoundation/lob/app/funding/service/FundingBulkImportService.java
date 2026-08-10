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
 * sibling scope), so lookups that don't yet know the exact scope (a {@code Parent Project Title} or an
 * Events-file reference) search broadly and fail with an "ambiguous reference" error if more than one
 * project shares that title — see {@link #findExistingProjectByTitle}.
 *
 * <p><b>Upsert semantics</b> (so re-uploading a file with one changed row is safe):
 * <ul>
 *   <li>Projects+Milestones file: a project (root or sub-project, arbitrary depth via
 *   {@code Parent Project Title}) is <em>created</em> when no project with its title exists yet in its
 *   parent scope, or <em>updated</em> in place when it does. Consecutive rows sharing the same
 *   ({@code Parent Project Title}, {@code Project Title}) pair are grouped into one project with
 *   multiple milestones — one milestone per row, created or updated the same way by
 *   {@code Milestone Title} within that project. A row with blank milestone columns declares the
 *   project alone. The project is resolved/saved once per group; each row's milestone (if any) is then
 *   upserted independently of its siblings, so one bad milestone row doesn't block the others — but if
 *   the project itself fails to resolve/create, every row in the group fails together (there is nothing
 *   to attach milestones to).</li>
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
    // Projects+Milestones file — upsert: create/update the project (root or sub, any depth via
    // Parent Project Title), then upsert each row's milestone (if any) independently.
    // -------------------------------------------------------------------------

    private ProjectsMilestonesFileOutcome processProjectsMilestonesFile(String organisationId, MultipartFile file, Map<String, String> resolvedProjectIds) {
        Either<ProblemDetail, List<ProjectMilestoneCsvLine>> parsed = projectMilestoneCsvParser.parseCsv(file, ProjectMilestoneCsvLine.class);
        if (parsed.isLeft()) {
            return new ProjectsMilestonesFileOutcome(fileLevelError(file, FundingCsvFileType.PROJECTS_MILESTONES, parsed.getLeft()), 0, 0, 0, 0);
        }

        List<ProjectMilestoneCsvLine> lines = parsed.get();
        // Group consecutive/duplicate rows sharing the same (parent, title) pair — the project is
        // upserted once per group, and each grouped row's milestone columns (if any) are upserted as
        // their own milestone, independently of one another.
        LinkedHashMap<String, List<Integer>> groups = groupByKey(lines, FundingBulkImportService::projectGroupKey);

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
            if (outcome.projectCreated() != null) {
                if (outcome.projectCreated()) projectsCreated++; else projectsUpdated++;
            }
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

    private static String projectGroupKey(ProjectMilestoneCsvLine line) {
        return nullToEmpty(line.getParentProjectTitle()) + "||" + nullToEmpty(line.getProjectTitle());
    }

    /**
     * Upserts one group's project once, then upserts each row's milestone independently of the
     * others. If the project itself fails to resolve/create, every row in the group reports the same
     * error (there is nothing to attach a milestone to).
     */
    private ProjectMilestoneGroupOutcome processProjectMilestoneGroup(String organisationId, List<ProjectMilestoneCsvLine> lines,
            List<Integer> idxs, Map<String, String> resolvedProjectIds) {

        ProjectMilestoneCsvLine first = lines.get(idxs.get(0));
        if (isBlank(first.getProjectTitle())) {
            return groupFailure(idxs, Problems.badRequest("Project Title is required", ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }

        Either<ProblemDetail, String> parentIdE = resolveOptionalParentId(organisationId, first.getParentProjectTitle(), resolvedProjectIds);
        if (parentIdE.isLeft()) {
            return groupFailure(idxs, parentIdE.getLeft());
        }

        Either<ProblemDetail, UpsertOutcome<ProjectEntity>> projectE = upsertProject(organisationId, first, parentIdE.get(), resolvedProjectIds);
        if (projectE.isLeft()) {
            return groupFailure(idxs, projectE.getLeft());
        }
        ProjectEntity project = projectE.get().entity();

        List<FundingRowError> errors = new ArrayList<>();
        int succeeded = 1; // the project row itself
        int milestonesCreated = 0;
        int milestonesUpdated = 0;
        for (int idx : idxs) {
            ProjectMilestoneCsvLine line = lines.get(idx);
            if (!line.hasMilestone()) {
                continue;
            }
            Either<ProblemDetail, Boolean> result = upsertMilestoneRow(project, line);
            if (result.isLeft()) {
                errors.add(rowError(idx + 1, result.getLeft()));
                continue;
            }
            succeeded++;
            if (result.get()) milestonesCreated++; else milestonesUpdated++;
        }

        return new ProjectMilestoneGroupOutcome(errors, succeeded, projectE.get().created(), milestonesCreated, milestonesUpdated);
    }

    private static ProjectMilestoneGroupOutcome groupFailure(List<Integer> idxs, ProblemDetail problem) {
        List<FundingRowError> errors = idxs.stream().map(idx -> rowError(idx + 1, problem)).toList();
        return new ProjectMilestoneGroupOutcome(errors, 0, null, 0, 0);
    }

    /** Blank means "no parent" (a root project); otherwise resolves the referenced existing project's id. */
    private Either<ProblemDetail, String> resolveOptionalParentId(String organisationId, String parentProjectTitle,
            Map<String, String> resolvedProjectIds) {
        if (isBlank(parentProjectTitle)) {
            return Either.right(null);
        }
        return resolveProjectId(organisationId, parentProjectTitle, resolvedProjectIds);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> upsertProject(String organisationId,
            ProjectMilestoneCsvLine first, String parentId, Map<String, String> resolvedProjectIds) {

        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProjectByTitle(organisationId, first.getProjectTitle());
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        Optional<ProjectEntity> existing = existingE.get();

        Either<ProblemDetail, BigDecimal> totalAmountE = parseDecimal(first.getTotalAmount(), "Total Amount");
        if (totalAmountE.isLeft()) {
            return Either.left(totalAmountE.getLeft());
        }

        return existing.isEmpty()
                ? createProject(organisationId, first, totalAmountE.get(), parentId, resolvedProjectIds)
                : updateProject(first, existing.get(), totalAmountE.get(), parentId, resolvedProjectIds);
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> createProject(String organisationId, ProjectMilestoneCsvLine first,
            BigDecimal totalAmount, String parentId, Map<String, String> resolvedProjectIds) {

        // CREATE — full data is required.
        if (totalAmount == null) {
            return Either.left(Problems.badRequest(
                    "Total Amount is required to create project: " + first.getProjectTitle(), ErrorTitleConstants.PROJECT_AMOUNT_INVALID));
        }
        if (isBlank(first.getCurrency())) {
            return Either.left(Problems.badRequest(
                    "Currency is required to create project: " + first.getProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }

        ProjectEntity created;
        if (parentId == null) {
            ProjectView view = projectService.createWithMilestones(ProjectWithMilestonesCreateRequest.builder()
                    .organisationId(organisationId)
                    .projectTitle(first.getProjectTitle())
                    .fundingId(blankToNull(first.getFundingId()))
                    .totalAmount(totalAmount)
                    .currency(first.getCurrency())
                    .build());
            Optional<ProblemDetail> error = view.getError();
            if (error.isPresent()) {
                return Either.left(error.get());
            }
            created = projectRepository.findById(view.getProjectId()).orElseThrow();
        } else {
            ProjectEntity parent = projectRepository.findById(parentId).orElseThrow();
            Either<ProblemDetail, ProjectEntity> subResult = projectStructureService.createSubProject(
                    parent, first.getProjectTitle(), blankToNull(first.getFundingId()), totalAmount, first.getCurrency());
            if (subResult.isLeft()) {
                return Either.left(subResult.getLeft());
            }
            created = subResult.get();
        }
        resolvedProjectIds.put(first.getProjectTitle(), created.getId());
        return Either.right(new UpsertOutcome<>(created, true));
    }

    private Either<ProblemDetail, UpsertOutcome<ProjectEntity>> updateProject(ProjectMilestoneCsvLine first, ProjectEntity existing,
            BigDecimal totalAmount, String parentId, Map<String, String> resolvedProjectIds) {

        // UPDATE — partial: a blank CSV cell means "leave this field unchanged". projectTitle is
        // never sent — it's immutable and we already matched the existing row by its exact title.
        ProjectUpdateRequest updateRequest = ProjectUpdateRequest.builder()
                .totalAmount(totalAmount)
                .currency(blankToNull(first.getCurrency()))
                .parentProjectId(parentId)
                .build();
        ProjectView view = projectService.updateProject(existing.getId(), updateRequest);
        Optional<ProblemDetail> error = view.getError();
        if (error.isPresent()) {
            return Either.left(error.get());
        }
        resolvedProjectIds.put(first.getProjectTitle(), existing.getId());
        return Either.right(new UpsertOutcome<>(existing, false));
    }

    /** Returns {@code Right(true)} when a new milestone was created, {@code Right(false)} when an existing one was updated. */
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

        Optional<MilestoneEntity> existing = milestoneService.findByProjectIdAndMilestoneTitle(project.getId(), line.getMilestoneTitle());
        if (existing.isPresent()) {
            // UPDATE — partial: a blank CSV cell means "leave this field unchanged". milestoneTitle is
            // never sent — it's immutable and we already matched the existing row by its exact title.
            MilestoneUpdateRequest updateRequest = MilestoneUpdateRequest.builder()
                    .milestoneAmount(amount)
                    .currency(blankToNull(line.getMilestoneCurrency()))
                    .milestoneDate(date)
                    .build();
            MilestoneView view = milestoneService.updateMilestone(project.getId(), existing.get().getId(), updateRequest);
            Optional<ProblemDetail> error = view.getError();
            if (error.isPresent()) {
                return Either.left(error.get());
            }
            return Either.right(false);
        }

        // CREATE — full data is required.
        if (amount == null || isBlank(line.getMilestoneCurrency()) || date == null) {
            return Either.left(Problems.badRequest(
                    "milestoneAmount, milestoneCurrency and milestoneDate are required to create milestone: " + line.getMilestoneTitle(),
                    ErrorTitleConstants.MILESTONE_FIELDS_REQUIRED));
        }
        MilestoneCreateRequest request = MilestoneCreateRequest.builder()
                .milestoneTitle(line.getMilestoneTitle())
                .milestoneAmount(amount)
                .currency(line.getMilestoneCurrency())
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
     * Resolves a CSV projectTitle reference to an internal project id — first against projects
     * created/updated earlier in this same import call, then against the database. Returns an error
     * when no project matches, or when more than one project in the organisation shares that title
     * (a bare title is only guaranteed unique within its sibling scope, not organisation-wide). Never
     * creates anything.
     */
    private Either<ProblemDetail, String> resolveProjectId(String organisationId, String projectTitle, Map<String, String> resolvedProjectIds) {
        String cached = resolvedProjectIds.get(projectTitle);
        if (cached != null) {
            return Either.right(cached);
        }
        Either<ProblemDetail, Optional<ProjectEntity>> existingE = findExistingProjectByTitle(organisationId, projectTitle);
        if (existingE.isLeft()) {
            return Either.left(existingE.getLeft());
        }
        return existingE.get()
                .map(project -> {
                    resolvedProjectIds.put(projectTitle, project.getId());
                    return Either.<ProblemDetail, String>right(project.getId());
                })
                .orElseGet(() -> Either.left(Problems.projectReferenceNotFound(projectTitle)));
    }

    /** Same resolution as {@link #resolveProjectId}, but returns the full entity — needed to inspect {@code parentProject}. */
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

    /** {@code projectCreated} is null when the group failed before the project itself could be resolved. */
    private record ProjectMilestoneGroupOutcome(List<FundingRowError> errors, int succeeded, Boolean projectCreated,
            int milestonesCreated, int milestonesUpdated) {
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
        private int projectsUpdated;
        private int milestonesCreated;
        private int milestonesUpdated;
        private int eventsCreated;
        private int allocationsCreated;

        void addProjectsMilestones(ProjectsMilestonesFileOutcome outcome) {
            projectsCreated += outcome.projectsCreated();
            projectsUpdated += outcome.projectsUpdated();
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
                    .milestonesCreated(milestonesCreated)
                    .eventsCreated(eventsCreated)
                    .allocationsCreated(allocationsCreated)
                    .projectsUpdated(projectsUpdated)
                    .milestonesUpdated(milestonesUpdated)
                    .build();
        }
    }

}
