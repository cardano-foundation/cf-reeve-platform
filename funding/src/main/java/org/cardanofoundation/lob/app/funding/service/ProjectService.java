package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.ProjectLockStatus;
import org.cardanofoundation.lob.app.funding.domain.request.MilestoneCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectTreeNodeRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectUpdateRequest;
import org.cardanofoundation.lob.app.funding.domain.request.ProjectWithMilestonesCreateRequest;
import org.cardanofoundation.lob.app.funding.domain.view.MilestoneView;
import org.cardanofoundation.lob.app.funding.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectDraftStatusView;
import org.cardanofoundation.lob.app.funding.domain.view.ProjectView;
import org.cardanofoundation.lob.app.funding.domain.view.SpendingEventView;
import org.cardanofoundation.lob.app.funding.repository.EventMilestoneAllocationRepository;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final SpendingEventService spendingEventService;
    private final ProjectStructureService projectStructureService;
    private final EventMilestoneAllocationRepository allocationRepository;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;
    private final FundingCascadeDeleteService cascadeDeleteService;

    // -------------------------------------------------------------------------
    // View-returning API (used by the controller — carries the ProblemDetail)
    // -------------------------------------------------------------------------

    public PagedResponse<ProjectView> listProjects(String organisationId, Pageable pageable) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return PagedResponse.error(Problems.unauthorized());
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return PagedResponse.error(Problems.organisationNotFound(organisationId));
        }
        return PagedResponse.of(projectRepository.findByOrganisationId(organisationId, pageable), this::toView);
    }

    public ProjectView getProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(projectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        // Get-by-id returns the full detail: milestones, sub-projects and the associated events.
        return toView(projectM.get(), true);
    }

    /**
     * Whether the project has at least one linked event still in Draft status, anywhere in its own
     * subtree — the edit flow calls this before opening the edit form to decide whether to show the
     * draft warning (LOB-2365). Same subtree scope as the published-event lock check in
     * {@link #updateProject}, just checking {@code DRAFT} instead of {@code PUBLISHED}. {@code organisationId}
     * is required explicitly rather than only derived from the loaded project, matching this module's
     * other explicit-organisationId endpoints (e.g. {@link #listProjects}) — a project id belonging to a
     * different organisation than the one supplied is treated as not found, same as an unknown id.
     */
    public ProjectDraftStatusView hasDraftEvent(String organisationId, String projectId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return ProjectDraftStatusView.error(Problems.unauthorized());
        }
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty() || !projectM.get().getOrganisationId().equals(organisationId)) {
            return ProjectDraftStatusView.error(Problems.projectNotFound(projectId));
        }
        boolean hasDraft = allocationRepository.existsByMilestoneProjectIdInAndEventStatus(
                ProjectTreeSupport.subtreeProjectIds(projectRepository, projectId), EventStatus.DRAFT);
        return ProjectDraftStatusView.builder().hasDraftEvent(hasDraft).build();
    }

    public PagedResponse<ProjectView> listSubProjects(String parentProjectId, Pageable pageable) {
        Optional<ProjectEntity> parentM = projectRepository.findById(parentProjectId);
        if (parentM.isEmpty()) {
            return PagedResponse.error(Problems.projectNotFound(parentProjectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(parentM.get().getOrganisationId())) {
            return PagedResponse.error(Problems.unauthorized());
        }
        return PagedResponse.of(projectRepository.findByParentProjectId(parentProjectId, pageable), this::toView);
    }

    /**
     * Creates a project together with its milestones <em>or</em> its sub-project tree (each node again
     * having milestones or sub-projects), in one atomic call. Any validation failure rolls the whole
     * tree back. The milestones-XOR-sub-projects rule and every amount/date validation apply per node.
     */
    @Transactional
    public ProjectView createWithMilestones(ProjectWithMilestonesCreateRequest request) {
        Optional<ProblemDetail> xor = FundingValidations.milestonesXorSubProjects(
                !request.getMilestones().isEmpty(), !request.getSubProjects().isEmpty());
        if (xor.isPresent()) {
            return ProjectView.error(xor.get());
        }

        // When a parentProjectId is supplied, create the project as a sub-project of that (existing)
        // parent — through the same shared creation path the event-allocation flow uses.
        Either<ProblemDetail, ProjectEntity> created = request.getParentProjectId() != null
                ? resolveParent(request).flatMap(parent -> projectStructureService.createSubProject(
                        parent, request.getProjectTitle(),
                        request.getFundingId(), request.getTotalAmount(), request.getCurrency()))
                : createRootProject(request);
        if (created.isLeft()) {
            return ProjectView.error(created.getLeft());
        }

        ProjectEntity projectEntity = created.get();
        Optional<ProblemDetail> childrenProblem = createNodeChildren(
                projectEntity, request.getMilestones(), request.getSubProjects());
        if (childrenProblem.isPresent()) {
            // Keep creation atomic: roll the whole tree back on any failure.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            return ProjectView.error(childrenProblem.get());
        }
        return toView(projectEntity);
    }

    private Either<ProblemDetail, ProjectEntity> createRootProject(ProjectWithMilestonesCreateRequest request) {
        // Unlike a sub-project (which defaults to its parent's currency — see
        // ProjectStructureService.createSubProject), a root project has no parent to inherit from, so
        // its currency must be given explicitly. The DTO itself no longer enforces this via @NotBlank
        // since the same field is also used for sub-project creation, where it's optional.
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            return Either.left(Problems.badRequest(
                    "Currency is required to create a root project: " + request.getProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency(),
                milestoneService.isCurrencyRegisteredAndActive(request.getOrganisationId(), request.getCurrency()));
        if (currencyProblem.isPresent()) {
            return Either.left(currencyProblem.get());
        }
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return Either.left(amountProblem.get());
        }
        if (projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(
                request.getOrganisationId(), request.getProjectTitle())) {
            return Either.left(Problems.conflict(
                    "Project title already exists in this organisation: " + request.getProjectTitle(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        Optional<ProblemDetail> fundingIdProblem = projectStructureService.fundingIdAvailable(
                request.getOrganisationId(), request.getFundingId());
        if (fundingIdProblem.isPresent()) {
            return Either.left(fundingIdProblem.get());
        }
        // A root project's proId is user-suppliable (unlike a sub-project's or milestone's, which are
        // always system-assigned — see ProjectEntity#getProId()) and, per the product design, mandatory:
        // it's the value a caller must hold onto to reliably reference this project later (e.g. a CSV
        // update row), since title alone can drift after a rename. The DTO itself doesn't enforce this
        // via @NotBlank since the same field is also used for sub-project creation, where it's ignored
        // (see the Currency check above for the same reasoning) — so it's checked explicitly here,
        // matching the FE's own required-field treatment of this input.
        if (request.getProId() == null || request.getProId().isBlank()) {
            return Either.left(Problems.badRequest(
                    "Project ID is required to create a root project: " + request.getProjectTitle(), ErrorTitleConstants.PROJECT_FIELDS_REQUIRED));
        }
        String proId = request.getProId();
        // Caller-chosen, so it needs its own uniqueness pre-check — the title check above can't catch a
        // colliding proId on its own.
        if (projectRepository.existsByOrganisationIdAndProIdAndParentProjectIsNull(request.getOrganisationId(), proId)) {
            return Either.left(Problems.conflict(
                    "Project ID already exists in this organisation: " + proId,
                    ErrorTitleConstants.PROJECT_PROID_ALREADY_EXISTS));
        }
        // The primary key is derived from the proId (unique in this scope, checked above) — never from
        // the title, which is a freely editable field.
        String projectId = ProjectEntity.id(request.getOrganisationId(), proId);
        return Either.right(projectRepository.saveAndFlush(toEntity(request, projectId, proId)));
    }

    /** The parent for a project created as a sub-project: must exist and belong to the same organisation. */
    private Either<ProblemDetail, ProjectEntity> resolveParent(ProjectWithMilestonesCreateRequest request) {
        Optional<ProjectEntity> parentM = projectRepository.findById(request.getParentProjectId());
        if (parentM.isEmpty()) {
            return Either.left(Problems.notFound(
                    "Parent project not found: " + request.getParentProjectId(), ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND));
        }
        ProjectEntity parent = parentM.get();
        if (!parent.getOrganisationId().equals(request.getOrganisationId())) {
            return Either.left(Problems.badRequest(
                    "Parent project %s belongs to a different organisation".formatted(request.getParentProjectId()),
                    ErrorTitleConstants.PARENT_PROJECT_ORG_MISMATCH));
        }
        return Either.right(parent);
    }

    /**
     * Creates the children of {@code project}: either its milestones or its sub-projects (recursively).
     * Returns the first validation failure, or empty when the whole subtree was created.
     */
    private Optional<ProblemDetail> createNodeChildren(ProjectEntity project,
            List<MilestoneCreateRequest> milestones, List<ProjectTreeNodeRequest> subProjects) {

        for (MilestoneCreateRequest milestoneRequest : milestones) {
            Either<ProblemDetail, MilestoneEntity> result = milestoneService.create(project.getId(), milestoneRequest);
            if (result.isLeft()) {
                return Optional.of(result.getLeft());
            }
        }

        // Sub-project titles are unique within their parent — reject duplicate titles among the sibling
        // nodes of this request up front (a per-row DB check alone can miss same-request siblings).
        Optional<String> duplicateSubTitle = FundingValidations.firstDuplicate(
                subProjects.stream().map(ProjectTreeNodeRequest::getProjectTitle).toList());
        if (duplicateSubTitle.isPresent()) {
            return Optional.of(Problems.conflict(
                    "Duplicate sub-project title under the same parent: " + duplicateSubTitle.get(),
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        for (ProjectTreeNodeRequest node : subProjects) {
            Optional<ProblemDetail> nodeXor = FundingValidations.milestonesXorSubProjects(
                    !node.getMilestones().isEmpty(), !node.getSubProjects().isEmpty());
            if (nodeXor.isPresent()) {
                return nodeXor;
            }
            Either<ProblemDetail, ProjectEntity> subProject = projectStructureService.createSubProject(
                    project, node.getProjectTitle(),
                    node.getFundingId(), node.getTotalAmount(), node.getCurrency());
            if (subProject.isLeft()) {
                return Optional.of(subProject.getLeft());
            }

            Optional<ProblemDetail> childProblem = createNodeChildren(
                    subProject.get(), node.getMilestones(), node.getSubProjects());
            if (childProblem.isPresent()) {
                return childProblem;
            }
        }
        return Optional.empty();
    }

    @Transactional
    public ProjectView updateProject(String projectId, ProjectUpdateRequest request) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return ProjectView.error(Problems.projectNotFound(projectId));
        }
        ProjectEntity project = projectM.get();
        if (!keycloakSecurityHelper.canUserAccessOrg(project.getOrganisationId())) {
            return ProjectView.error(Problems.unauthorized());
        }
        // Project-level lock (LOB-2365): projectTitle, totalAmount, currency, and re-parenting are all
        // frozen project-wide once any PUBLISHED event exists anywhere in this project's own subtree —
        // once a linked event has gone on-chain, nothing about the project it references can change,
        // including its title. projectTitle is editable right up until that point (LOB-2384). This
        // replaces the old wholesale block, which used to reject the entire request the instant any
        // published event existed anywhere in the subtree, regardless of which field it touched.
        boolean titleChanging = request.getProjectTitle() != null && !request.getProjectTitle().equals(project.getProjectTitle());
        boolean touchesLockedProjectField = titleChanging || request.getTotalAmount() != null
                || request.getCurrency() != null || request.getParentProjectId() != null;
        if (touchesLockedProjectField && allocationRepository.existsByMilestoneProjectIdInAndEventStatus(
                ProjectTreeSupport.subtreeProjectIds(projectRepository, projectId), EventStatus.PUBLISHED)) {
            return ProjectView.error(Problems.conflict(
                    "Cannot update projectTitle, totalAmount, currency, or parentProjectId: project %s is locked because a published event exists in its structure"
                            .formatted(projectId),
                    ErrorTitleConstants.SPENDING_EVENT_ALREADY_PUBLISHED));
        }
        Optional<ProblemDetail> amountProblem = FundingValidations.projectAmount(request.getTotalAmount());
        if (amountProblem.isPresent()) {
            return ProjectView.error(amountProblem.get());
        }
        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(request.getCurrency(),
                milestoneService.isCurrencyRegisteredAndActive(project.getOrganisationId(), request.getCurrency()));
        if (currencyProblem.isPresent()) {
            return ProjectView.error(currencyProblem.get());
        }

        // A currency change cascades to every descendant sub-project and milestone (see
        // cascadeCurrency) — the lock above already rejects it once a PUBLISHED event exists anywhere
        // in the subtree; this flag just records whether a change was actually requested, for the
        // cascade call further down.
        boolean currencyChanging = request.getCurrency() != null && !request.getCurrency().equals(project.getCurrency());

        // The budget the project ends up with — parent-fit and child-coverage checks validate this value.
        BigDecimal effectiveTotal = request.getTotalAmount() != null ? request.getTotalAmount() : project.getTotalAmount();

        if (request.getTotalAmount() != null) {
            // Shrinking the budget below what children currently claim used to be rejected outright.
            // For an update specifically (creation still rejects outright — see
            // ProjectStructureService#createSubProject / ProjectService#createRootProject, neither of
            // which is reachable from here), that's relaxed instead: the edit goes through exactly as
            // typed — children's own recorded amounts, and every event's own allocated figures, are
            // never rewritten — and every draft event fully contained in this project's subtree is
            // marked ERROR instead, since a human now has to review and fix it before it can ever be
            // published (LOB-2365). An event that also reaches into a different, untouched project is
            // still a hard block, the same cross-project rule the existing delete-cascade already uses.
            Optional<ProblemDetail> coverage = FundingValidations.projectTotalCoversChildren(
                    effectiveTotal,
                    FundingValidations.sumMilestoneAmounts(milestoneService.findByProjectId(projectId), null),
                    FundingValidations.sumProjectTotals(projectRepository.findByParentProjectId(projectId), null));
            if (coverage.isPresent()) {
                Optional<ProblemDetail> blocked = cascadeDeleteService.markContainedEventsAsErrorOrBlock(projectId);
                if (blocked.isPresent()) {
                    return ProjectView.error(blocked.get());
                }
            }
            // A sub-project's new budget must still fit its (unchanged) parent.
            if (request.getParentProjectId() == null && project.getParentProject() != null) {
                ProjectEntity parent = project.getParentProject();
                BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                        projectRepository.findByParentProjectId(parent.getId()), project.getId());
                Optional<ProblemDetail> fit = FundingValidations.subProjectAmount(
                        effectiveTotal, project.getProjectTitle(), parent, otherSubProjectsTotal);
                if (fit.isPresent()) {
                    return ProjectView.error(fit.get());
                }
            }
        }

        if (request.getParentProjectId() != null) {
            Optional<ProblemDetail> parentProblem = assignParent(project, request.getParentProjectId(), effectiveTotal);
            if (parentProblem.isPresent()) {
                return ProjectView.error(parentProblem.get());
            }
        }
        // projectTitle is editable up until the project locks (see ProjectEntity#proId, which stays
        // fixed and is what everything that needs a stable reference uses instead) — still subject to
        // the same per-scope uniqueness title always had, checked against every sibling except this
        // project itself so an unchanged title never conflicts with its own prior value.
        String effectiveTitle = titleChanging ? request.getProjectTitle() : project.getProjectTitle();
        if (titleChanging || request.getParentProjectId() != null) {
            // A re-parent can also collide with a same-named sibling under the new parent, even when
            // the title itself doesn't change — projectTitleConflict below checks against the project's
            // *current* parent association, so this must run after assignParent (above) has already
            // updated it when both happen in the same request.
            Optional<ProblemDetail> titleConflict = projectTitleConflict(project, effectiveTitle);
            if (titleConflict.isPresent()) {
                return ProjectView.error(titleConflict.get());
            }
        }
        if (titleChanging) {
            project.setProjectTitle(request.getProjectTitle());
        }
        if (request.getTotalAmount() != null) project.setTotalAmount(request.getTotalAmount());
        if (currencyChanging) {
            cascadeCurrency(project, request.getCurrency());
        }
        return toView(projectRepository.saveAndFlush(project));
    }

    /**
     * Sets {@code project}'s currency to {@code currency} and propagates it down the whole subtree:
     * every descendant sub-project (recursively) and every milestone belonging to {@code project} or
     * any of those sub-projects. A sub-project's currency always mirrors its root's, and a
     * milestone's always mirrors its owning project's — there is no independent currency at either
     * level (see {@code ProjectStructureService#createSubProject} and CSV import's {@code Currency}
     * column, which only exists on the root row) — so once a currency changes, this must be the only
     * value left standing anywhere in the tree.
     */
    private void cascadeCurrency(ProjectEntity project, String currency) {
        project.setCurrency(currency);
        projectRepository.saveAndFlush(project);
        milestoneService.updateCurrencyForProject(project.getId(), currency);
        for (ProjectEntity child : projectRepository.findByParentProjectId(project.getId())) {
            cascadeCurrency(child, currency);
        }
    }

    /**
     * Attaches {@code project} under {@code parentProjectId} as a sub-project. The parent must exist,
     * belong to the same organisation, and assigning it must not introduce a cycle (i.e. the parent
     * may not be the project itself or one of its descendants). {@code effectiveTotal} is the budget
     * the project ends up with (an updated amount from the same request wins over the stored one).
     */
    private Optional<ProblemDetail> assignParent(ProjectEntity project, String parentProjectId, BigDecimal effectiveTotal) {
        Optional<ProjectEntity> parentM = projectRepository.findById(parentProjectId);
        if (parentM.isEmpty()) {
            return Optional.of(Problems.notFound(
                    "Parent project not found: " + parentProjectId, ErrorTitleConstants.PARENT_PROJECT_NOT_FOUND));
        }
        ProjectEntity parent = parentM.get();
        if (!parent.getOrganisationId().equals(project.getOrganisationId())) {
            return Optional.of(Problems.badRequest(
                    "Parent project %s belongs to a different organisation".formatted(parentProjectId),
                    ErrorTitleConstants.PARENT_PROJECT_ORG_MISMATCH));
        }
        if (createsCycle(project.getId(), parent)) {
            return Optional.of(Problems.badRequest(
                    "Assigning parent %s to project %s would create a circular dependency".formatted(parentProjectId, project.getId()),
                    ErrorTitleConstants.PROJECT_CIRCULAR_DEPENDENCY));
        }
        Optional<ProblemDetail> structure = FundingValidations.subProjectAllowed(milestoneService.hasMilestones(parent.getId()));
        if (structure.isPresent()) {
            return structure;
        }
        BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                projectRepository.findByParentProjectId(parent.getId()), project.getId());
        Optional<ProblemDetail> amountProblem = FundingValidations.subProjectAmount(
                effectiveTotal, project.getProjectTitle(), parent, otherSubProjectsTotal);
        if (amountProblem.isPresent()) {
            return amountProblem;
        }
        project.setParentProject(parent);
        return Optional.empty();
    }

    /**
     * A project title must be unique within its scope: root projects per organisation, sub-projects
     * within their parent. The check excludes the project itself so an unchanged title never conflicts.
     */
    private Optional<ProblemDetail> projectTitleConflict(ProjectEntity project, String title) {
        boolean exists = project.getParentProject() == null
                ? projectRepository.existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(
                        project.getOrganisationId(), title, project.getId())
                : projectRepository.existsByParentProjectIdAndProjectTitleAndIdNot(
                        project.getParentProject().getId(), title, project.getId());
        if (exists) {
            return Optional.of(Problems.conflict(
                    "Project title already exists in this scope: " + title,
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }
        return Optional.empty();
    }

    /**
     * True when {@code projectId} already appears in the candidate parent's ancestor chain — which
     * includes the candidate parent being the project itself (self-parenting) or one of its
     * descendants. Walking up the single parent link terminates at a root project.
     */
    private static boolean createsCycle(String projectId, ProjectEntity candidateParent) {
        ProjectEntity cursor = candidateParent;
        while (cursor != null) {
            if (projectId.equals(cursor.getId())) {
                return true;
            }
            cursor = cursor.getParentProject();
        }
        return false;
    }

    @Transactional
    public Optional<ProblemDetail> deleteProject(String projectId) {
        Optional<ProjectEntity> projectM = projectRepository.findById(projectId);
        if (projectM.isEmpty()) {
            return Optional.of(Problems.projectNotFound(projectId));
        }
        if (!keycloakSecurityHelper.canUserAccessOrg(projectM.get().getOrganisationId())) {
            return Optional.of(Problems.unauthorized());
        }
        // Cascade: fails when any published event is associated anywhere in the subtree; otherwise the
        // project, its sub-projects, milestones and the referencing draft-event allocations are removed.
        return cascadeDeleteService.deleteProjectSubtree(projectM.get());
    }

    // -------------------------------------------------------------------------
    // Internal lookups / mapping
    // -------------------------------------------------------------------------

    public Optional<ProjectEntity> findById(String projectId) {
        return projectRepository.findById(projectId);
    }

    public boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId) {
        return projectRepository.existsByOrganisationIdAndExternalProjectId(organisationId, externalProjectId);
    }

    /** List/summary view — milestones and sub-projects, without the (heavier) associated events. */
    public ProjectView toView(ProjectEntity project) {
        return toView(project, false);
    }

    /**
     * Builds the project view. When {@code includeEvents} is set, each project node additionally
     * carries the events allocated to its milestones (used by get-by-id); list endpoints omit them.
     */
    private ProjectView toView(ProjectEntity project, boolean includeEvents) {
        List<MilestoneView> milestoneViews = milestoneService.findByProjectId(project.getId()).stream()
                .map(milestoneService::toView)
                .toList();

        List<ProjectView> subProjectViews = projectRepository.findByParentProjectId(project.getId()).stream()
                .map(subProject -> toView(subProject, includeEvents))
                .toList();

        String parentProjectId = project.getParentProject() != null ? project.getParentProject().getId() : null;

        // Calculated (not stored): the project's spend rolls up its milestones' and sub-projects' spend.
        BigDecimal spentAmount = sumSpent(milestoneViews.stream().map(MilestoneView::getSpentAmount))
                .add(sumSpent(subProjectViews.stream().map(ProjectView::getSpentAmount)));

        return ProjectView.builder()
                .projectId(project.getId())
                .organisationId(project.getOrganisationId())
                .fundingId(project.getFundingId())
                .externalProjectId(project.getExternalProjectId())
                .projectTitle(project.getProjectTitle())
                .proId(project.getProId())
                .totalAmount(project.getTotalAmount())
                .currency(project.getCurrency())
                .parentProjectId(parentProjectId)
                .createdAt(project.getCreatedAt())
                .milestones(milestoneViews)
                .subProjects(subProjectViews)
                .spentAmount(spentAmount)
                .lockStatus(lockStatus(milestoneViews, subProjectViews))
                .events(includeEvents ? loadEvents(project.getId()) : null)
                .build();
    }

    /**
     * Computed purely from the already-built {@code milestoneViews} (each carrying its own
     * {@link MilestoneView#isLocked()}) and {@code subProjectViews} (each already carrying its own,
     * recursively-computed {@link ProjectView#getLockStatus()}) — no extra queries beyond what
     * building those views already required. LOB-2365.
     *
     * <p>A structurally empty node (no milestones and no sub-projects — e.g. a freshly-created project
     * nobody has added children to yet) is {@code EDITABLE}: there is nothing here to lock. This also
     * means an empty sub-project correctly keeps its ancestor from reading as fully {@code LOCKED} —
     * that ancestor can be {@code LOCKED} only once every child (recursively) is itself {@code LOCKED},
     * and an empty child's own status is {@code EDITABLE}, never {@code LOCKED}.
     */
    private static ProjectLockStatus lockStatus(List<MilestoneView> milestoneViews, List<ProjectView> subProjectViews) {
        if (milestoneViews.isEmpty() && subProjectViews.isEmpty()) {
            return ProjectLockStatus.EDITABLE;
        }
        boolean anyLocked = milestoneViews.stream().anyMatch(MilestoneView::isLocked)
                || subProjectViews.stream().anyMatch(sp -> sp.getLockStatus() != ProjectLockStatus.EDITABLE);
        if (!anyLocked) {
            return ProjectLockStatus.EDITABLE;
        }
        boolean allLocked = milestoneViews.stream().allMatch(MilestoneView::isLocked)
                && subProjectViews.stream().allMatch(sp -> sp.getLockStatus() == ProjectLockStatus.LOCKED);
        return allLocked ? ProjectLockStatus.LOCKED : ProjectLockStatus.PARTLY_LOCKED;
    }

    private static BigDecimal sumSpent(java.util.stream.Stream<BigDecimal> amounts) {
        return amounts.filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** All events allocated to the given project's milestones, as views. */
    private List<SpendingEventView> loadEvents(String projectId) {
        return spendingEventService
                .findByProjectIdAndFilter(projectId, Optional.empty(), Optional.empty(), Pageable.unpaged())
                .getContent().stream()
                .map(spendingEventService::toView)
                .toList();
    }

    private ProjectEntity toEntity(ProjectWithMilestonesCreateRequest request, String projectId, String proId) {
        return ProjectEntity.builder()
                .id(projectId)
                .organisationId(request.getOrganisationId())
                .fundingId(request.getFundingId())
                .projectTitle(request.getProjectTitle())
                .proId(proId)
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .build();
    }

}
