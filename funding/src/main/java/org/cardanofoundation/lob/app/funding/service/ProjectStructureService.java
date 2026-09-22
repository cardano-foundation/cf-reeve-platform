package org.cardanofoundation.lob.app.funding.service;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.ErrorTitleConstants;
import org.cardanofoundation.lob.app.funding.util.FundingValidations;
import org.cardanofoundation.lob.app.funding.util.Problems;

/**
 * Shared structural operations on the project tree. Both the create-project endpoint and the
 * event-allocation flow create sub-projects; this service holds the single implementation of the
 * validations they must agree on: the milestones-XOR-sub-projects rule, budget positivity, and the
 * parent-budget fit (individually and cumulatively across siblings).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStructureService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final ProjectChildSequenceService childSequenceService;

    /**
     * Creates a sub-project of {@code parent}, auto-assigning its proId (see the other overload's
     * Javadoc) — the path used by the UI-facing JSON API and the event-allocation flow, neither of
     * which ever supplies one.
     */
    @Transactional
    public Either<ProblemDetail, ProjectEntity> createSubProject(ProjectEntity parent,
            String projectTitle, @Nullable String fundingId, @Nullable BigDecimal totalAmount, @Nullable String currency) {
        return createSubProject(parent, projectTitle, null, fundingId, totalAmount, currency);
    }

    /**
     * Creates a sub-project of {@code parent} after applying the structural and budget rules.
     * The new project's id is the deterministic {@code (parentId, proId)} sub-id and
     * its organisation is inherited from the parent. When {@code currency} is null/blank, it
     * defaults to the parent's currency — mirroring how a milestone's currency is always taken
     * from its owning project rather than specified independently. The parent's own currency is
     * always populated by this point (a root project requires it to be created, and every
     * sub-project resolves and stores its own effective currency the same way), so this default
     * is available at any depth.
     *
     * <p>{@code explicitProId}, when supplied, is used as the new sub-project's proId as-is (after a
     * uniqueness check) instead of the usual system-assigned {@code parent.proId + "-" + n}. This is
     * CSV-bulk-import-only: the UI/API-facing overload above always passes {@code null}, since
     * sub-projects created there are always auto-numbered — CSV creation is the one path that requires
     * the caller to supply its own value (see LOB-2384's CSV-mandatory-ID decision), specifically
     * because CSV is the only surface where a user needs to know the value again later (to reference
     * this row in a subsequent Events file) without any export/lookup tooling.
     */
    @Transactional
    public Either<ProblemDetail, ProjectEntity> createSubProject(ProjectEntity parent, String projectTitle,
            @Nullable String explicitProId, @Nullable String fundingId, @Nullable BigDecimal totalAmount, @Nullable String currency) {

        String effectiveCurrency = (currency != null && !currency.isBlank()) ? currency : parent.getCurrency();

        Optional<ProblemDetail> currencyProblem = FundingValidations.currencyCode(
                currency, milestoneService.isCurrencyRegisteredAndActive(parent.getOrganisationId(), currency));
        if (currencyProblem.isPresent()) {
            return Either.left(currencyProblem.get());
        }

        Optional<ProblemDetail> structure = FundingValidations.subProjectAllowed(
                milestoneService.hasMilestones(parent.getId()));
        if (structure.isPresent()) {
            return Either.left(structure.get());
        }

        Optional<ProblemDetail> amount = FundingValidations.projectAmount(totalAmount);
        if (amount.isPresent()) {
            return Either.left(amount.get());
        }

        if (projectRepository.existsByParentProjectIdAndProjectTitle(parent.getId(), projectTitle)) {
            return Either.left(Problems.conflict(
                    "Sub-project title already exists under this parent: " + projectTitle,
                    ErrorTitleConstants.PROJECT_TITLE_ALREADY_EXISTS));
        }

        Optional<ProblemDetail> fundingIdProblem = fundingIdAvailable(parent.getOrganisationId(), fundingId);
        if (fundingIdProblem.isPresent()) {
            return Either.left(fundingIdProblem.get());
        }

        BigDecimal otherSubProjectsTotal = FundingValidations.sumProjectTotals(
                projectRepository.findByParentProjectId(parent.getId()), null);
        Optional<ProblemDetail> subAmount = FundingValidations.subProjectAmount(totalAmount, projectTitle, parent, otherSubProjectsTotal);
        if (subAmount.isPresent()) {
            return Either.left(subAmount.get());
        }

        String proId;
        if (explicitProId != null && !explicitProId.isBlank()) {
            // CSV path only — see this method's Javadoc. Needs its own uniqueness pre-check since,
            // unlike the auto-assigned case, a caller-chosen value isn't guaranteed unique by
            // construction.
            if (projectRepository.existsByParentProjectIdAndProId(parent.getId(), explicitProId)) {
                return Either.left(Problems.conflict(
                        "Sub Project ID already exists under this parent: " + explicitProId,
                        ErrorTitleConstants.PROJECT_PROID_ALREADY_EXISTS));
            }
            proId = explicitProId;
        } else {
            proId = childSequenceService.nextChildProId(parent);
        }

        return Either.right(projectRepository.saveAndFlush(ProjectEntity.builder()
                .id(ProjectEntity.subId(parent.getId(), proId)) // derived from proId, never from the editable title
                .organisationId(parent.getOrganisationId())
                .fundingId(fundingId)
                .projectTitle(projectTitle)
                .proId(proId)
                .totalAmount(totalAmount)
                .currency(effectiveCurrency)
                .parentProject(parent)
                .build()));
    }

    /**
     * A project's {@code fundingId} is unique per organisation (DB constraint
     * {@code uq_funding_project_org_funding_id}) — validated here so callers get a clean 409
     * instead of a data-integrity 500. A null fundingId is always available.
     */
    public Optional<ProblemDetail> fundingIdAvailable(String organisationId, @Nullable String fundingId) {
        if (fundingId != null && projectRepository.existsByOrganisationIdAndFundingId(organisationId, fundingId)) {
            return Optional.of(Problems.conflict(
                    "fundingId %s is already used by another project".formatted(fundingId),
                    ErrorTitleConstants.PROJECT_FUNDING_ID_ALREADY_USED));
        }
        return Optional.empty();
    }

}
