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
    boolean existsByProjectIdAndMilestoneTitle(String projectId, String milestoneTitle);

    boolean existsByProjectIdAndMilestoneTitleAndIdNot(String projectId, String milestoneTitle, String id);

    /** Resolves a milestone only when it actually belongs to the given project (ownership check). */
    Optional<MilestoneEntity> findByIdAndProjectId(String id, String projectId);

    /** Resolves a milestone only when it belongs to the given project and that project belongs to the given organisation. */
    Optional<MilestoneEntity> findByIdAndProjectIdAndProject_OrganisationId(String id, String projectId, String organisationId);

}
