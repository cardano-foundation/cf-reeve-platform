package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;

public interface MilestoneRepository extends JpaRepository<MilestoneEntity, String> {

    List<MilestoneEntity> findByProjectId(String projectId);

    Page<MilestoneEntity> findByProjectId(String projectId, Pageable pageable);

    boolean existsByProjectId(String projectId);

    Optional<MilestoneEntity> findByProjectIdAndExternalMilestoneId(String projectId, String externalMilestoneId);

    /** Resolves a milestone only when it actually belongs to the given project (ownership check). */
    Optional<MilestoneEntity> findByIdAndProjectId(String id, String projectId);

}
