package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;

public interface FundingProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByOrganisationId(String organisationId);

    Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable);

    boolean existsByOrganisationIdAndProjectId(String organisationId, String projectId);

    List<ProjectEntity> findByParentProject_Id(String parentProjectUid);

}
