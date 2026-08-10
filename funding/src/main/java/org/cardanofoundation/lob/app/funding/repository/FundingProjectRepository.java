package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;

public interface FundingProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findByOrganisationId(String organisationId);

    Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable);

    boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId);

    boolean existsByOrganisationIdAndFundingId(String organisationId, String fundingId);

    // Title uniqueness: root titles are unique per organisation, sub-project titles within their parent.
    // A bare title is not globally unique across the whole org (sub-project titles only need to be
    // unique among siblings), so this broad any-level lookup is used only where the caller checks for
    // ambiguity — see FundingBulkImportService.findExistingProjectByTitle.
    List<ProjectEntity> findByOrganisationIdAndProjectTitle(String organisationId, String projectTitle);

    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(String organisationId, String projectTitle);

    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(String organisationId, String projectTitle, String id);

    // Exact, unambiguous scoped lookups (DB-constraint-backed) — used by the bulk CSV importer, which
    // always knows a project's exact scope (root, or a specific parent) from the same CSV row.
    Optional<ProjectEntity> findByOrganisationIdAndProjectTitleAndParentProjectIsNull(String organisationId, String projectTitle);

    Optional<ProjectEntity> findByParentProjectIdAndProjectTitle(String parentProjectId, String projectTitle);

    boolean existsByParentProjectIdAndProjectTitle(String parentProjectId, String projectTitle);

    boolean existsByParentProjectIdAndProjectTitleAndIdNot(String parentProjectId, String projectTitle, String id);

    List<ProjectEntity> findByParentProjectId(String parentProjectId);

    Page<ProjectEntity> findByParentProjectId(String parentProjectId, Pageable pageable);

    boolean existsByParentProjectId(String parentProjectId);

}
