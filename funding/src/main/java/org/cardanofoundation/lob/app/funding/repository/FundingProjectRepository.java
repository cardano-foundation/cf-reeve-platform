package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;

public interface FundingProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByOrganisationId(String organisationId);

    Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable);

    boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId);

    List<ProjectEntity> findByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId);

    boolean existsByOrganisationIdAndFundingId(String organisationId, String fundingId);

    // Title uniqueness: root titles are unique per organisation, sub-project titles within their parent.
    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(String organisationId, String projectTitle);

    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(String organisationId, String projectTitle, String id);

    boolean existsByParentProjectIdAndProjectTitle(String parentProjectId, String projectTitle);

    boolean existsByParentProjectIdAndProjectTitleAndIdNot(String parentProjectId, String projectTitle, String id);

    List<ProjectEntity> findByParentProjectId(String parentProjectId);

    Page<ProjectEntity> findByParentProjectId(String parentProjectId, Pageable pageable);

    boolean existsByParentProjectId(String parentProjectId);

}
