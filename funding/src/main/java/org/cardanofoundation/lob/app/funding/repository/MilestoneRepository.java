package org.cardanofoundation.lob.app.funding.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;

public interface MilestoneRepository extends JpaRepository<MilestoneEntity, String> {

    List<MilestoneEntity> findByProjectId(String projectId);

    List<MilestoneEntity> findByProjectIdIn(Collection<String> projectIds);

    Page<MilestoneEntity> findByProjectId(String projectId, Pageable pageable);

    boolean existsByProjectId(String projectId);

    Optional<MilestoneEntity> findByProjectIdAndExternalMilestoneId(String projectId, String externalMilestoneId);

    // Milestone titles are unique within their project.
    Optional<MilestoneEntity> findByProjectIdAndMilestoneTitle(String projectId, String milestoneTitle);

    boolean existsByProjectIdAndMilestoneTitle(String projectId, String milestoneTitle);

    boolean existsByProjectIdAndMilestoneTitleAndIdNot(String projectId, String milestoneTitle, String id);

    // proId is permanent (never updated after creation, see MilestoneEntity#proId) — lets a renamed
    // milestone keep being found by its stable identifier instead of its (now-changed) title.
    Optional<MilestoneEntity> findByProjectIdAndProId(String projectId, String proId);

    // A milestone's proId is normally system-assigned (never colliding by construction) — this is
    // needed only for the CSV-bulk-import path, which is allowed to supply its own value explicitly
    // and therefore needs its own pre-check.
    boolean existsByProjectIdAndProId(String projectId, String proId);

    /** Resolves a milestone only when it actually belongs to the given project (ownership check). */
    Optional<MilestoneEntity> findByIdAndProjectId(String id, String projectId);

}
