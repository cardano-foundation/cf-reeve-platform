package org.cardanofoundation.lob.app.funding.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;

public interface FundingProjectRepository extends JpaRepository<ProjectEntity, String> {

    /**
     * Row-locked read of a project, used only to atomically increment {@code nextChildSequence} when
     * assigning a new child's auto-generated proId (see {@code ProjectEntity#getProId()} /
     * {@code ProjectChildSequenceService}) — without this lock, two children created for the same
     * parent at nearly the same instant could read-and-increment the same counter value and collide.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectEntity> findWithLockById(String id);

    List<ProjectEntity> findByOrganisationId(String organisationId);

    Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable);

    // Root-only paged listing — same paginated-list convention as findByOrganisationId above (used by
    // ProjectService#listProjects), scoped to roots for FundingCsvExportService, which expands each
    // page's roots into their full sub-project/milestone tree.
    Page<ProjectEntity> findByOrganisationIdAndParentProjectIsNull(String organisationId, Pageable pageable);

    /** Same as above, additionally filtered to a caller-supplied set of root proIds — see FundingCsvExportService. */
    Page<ProjectEntity> findByOrganisationIdAndProIdInAndParentProjectIsNull(
            String organisationId, Collection<String> proIds, Pageable pageable);

    boolean existsByOrganisationIdAndExternalProjectId(String organisationId, String externalProjectId);

    boolean existsByOrganisationIdAndFundingId(String organisationId, String fundingId);

    // Title uniqueness: root titles are unique per organisation, sub-project titles within their parent.
    // A bare title is not globally unique across the whole org (sub-project titles only need to be
    // unique among siblings), so this broad any-level lookup is used only where the caller checks for
    // ambiguity — see FundingBulkImportService.findExistingProjectByTitle.
    List<ProjectEntity> findByOrganisationIdAndProjectTitle(String organisationId, String projectTitle);

    /** proId equivalent of the lookup above — see its Javadoc; same "search broadly, caller checks ambiguity" contract. */
    List<ProjectEntity> findByOrganisationIdAndProId(String organisationId, String proId);

    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNull(String organisationId, String projectTitle);

    boolean existsByOrganisationIdAndProjectTitleAndParentProjectIsNullAndIdNot(String organisationId, String projectTitle, String id);

    // Exact, unambiguous scoped lookups (DB-constraint-backed) — used by the bulk CSV importer, which
    // always knows a project's exact scope (root, or a specific parent) from the same CSV row.
    Optional<ProjectEntity> findByOrganisationIdAndProjectTitleAndParentProjectIsNull(String organisationId, String projectTitle);

    Optional<ProjectEntity> findByParentProjectIdAndProjectTitle(String parentProjectId, String projectTitle);

    boolean existsByParentProjectIdAndProjectTitle(String parentProjectId, String projectTitle);

    // proId is permanent (never updated after creation, see ProjectEntity#proId) — these are the
    // lookups that let a renamed project keep being found by its stable identifier instead of its
    // (now-changed) title. Scoped exactly like the title lookups above: root projects per
    // organisation, sub-projects per parent.
    Optional<ProjectEntity> findByOrganisationIdAndProIdAndParentProjectIsNull(String organisationId, String proId);

    Optional<ProjectEntity> findByParentProjectIdAndProId(String parentProjectId, String proId);

    // A sub-project's proId is normally system-assigned (never colliding by construction) — this is
    // needed only for the CSV-bulk-import path, which is allowed to supply its own value explicitly
    // (see ProjectStructureService#createSubProject's explicitProId overload) and therefore needs its
    // own pre-check, same reasoning as the root-scope check above.
    boolean existsByParentProjectIdAndProId(String parentProjectId, String proId);

    // A root project's proId is user-suppliable (unlike a sub-project's or milestone's, which are
    // always system-assigned — see ProjectEntity#getProId()), so — unlike those — it needs an explicit
    // pre-check at creation to turn a collision into a clean conflict instead of a raw DB constraint
    // violation. Only needed for the root scope; a sub-project's auto-generated proId can never collide
    // by construction.
    boolean existsByOrganisationIdAndProIdAndParentProjectIsNull(String organisationId, String proId);

    boolean existsByParentProjectIdAndProjectTitleAndIdNot(String parentProjectId, String projectTitle, String id);

    List<ProjectEntity> findByParentProjectId(String parentProjectId);

    Page<ProjectEntity> findByParentProjectId(String parentProjectId, Pageable pageable);

    boolean existsByParentProjectId(String parentProjectId);

}
