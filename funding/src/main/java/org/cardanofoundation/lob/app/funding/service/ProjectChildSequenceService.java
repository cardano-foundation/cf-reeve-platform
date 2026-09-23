package org.cardanofoundation.lob.app.funding.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;

/**
 * Assigns the auto-generated {@code proId} suffix for a project's children — sub-projects and
 * milestones alike, since a project has one or the other, never both (see
 * {@link FundingValidations#milestonesXorSubProjects}). A standalone service (rather than living on
 * {@link ProjectStructureService} or {@link MilestoneService} directly) so both can depend on it
 * without a circular dependency between them.
 */
@Service
@RequiredArgsConstructor
class ProjectChildSequenceService {

    private final FundingProjectRepository projectRepository;

    /**
     * Atomically increments {@code parent}'s {@code nextChildSequence} and returns the new child's
     * proId, {@code "<parent proId>-<n>"}. Locks {@code parent}'s row for the duration of the caller's
     * transaction (see {@link FundingProjectRepository#findWithLockById}) so two children created for
     * the same parent at nearly the same instant can never be assigned the same number — the second
     * caller blocks until the first commits (or rolls back) rather than racing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String nextChildProId(ProjectEntity parent) {
        ProjectEntity locked = projectRepository.findWithLockById(parent.getId()).orElseThrow(() ->
                new IllegalStateException("Project disappeared mid-transaction: " + parent.getId()));
        int next = locked.getNextChildSequence() + 1;
        locked.setNextChildSequence(next);
        projectRepository.saveAndFlush(locked);
        // parent (the caller's own, possibly not-yet-flushed instance) may be a different Java object
        // than locked when parent is a brand-new row already in this same persistence context — keep
        // it in sync so the caller's subsequent use of parent.getNextChildSequence() (if any) is correct.
        parent.setNextChildSequence(next);
        return locked.getProId() + "-" + next;
    }

}
