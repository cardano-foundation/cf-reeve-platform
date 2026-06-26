package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;

public interface MilestoneRepository extends JpaRepository<MilestoneEntity, String> {

    List<MilestoneEntity> findByProject_Id(String projectUid);

    Page<MilestoneEntity> findByProject_Id(String projectUid, Pageable pageable);

    Optional<MilestoneEntity> findByProject_IdAndExternalMilestoneId(String projectId, String externalMilestoneId);

}
