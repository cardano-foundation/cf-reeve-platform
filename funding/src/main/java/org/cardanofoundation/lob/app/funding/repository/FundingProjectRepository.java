package org.cardanofoundation.lob.app.funding.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Resolves a project only when it actually belongs to the given organisation (ownership check). */
    List<ProjectEntity> findByIdAndOrganisationId(String id, String organisationId);

    Page<ProjectEntity> findByOrganisationId(String organisationId, Pageable pageable);

    /**
     * Ids of the organisation's root projects that pass the projects-list filters which depend on a root's
     * whole structure; a {@code null} argument disables its filter. Native because sub-projects nest to any
     * depth — the recursive CTE maps every project to its root. Search matches the root, any sub-project or
     * any milestone title beneath it; the amount range compares against the {@code amountTarget} value shown
     * in the Projects table (BUDGETED = total amount, SPENT = allocated SPENDING, FUNDED = allocated FUNDING
     * minus allocated REFUND, all event statuses, rolled up over the root's whole structure). Allocations to a
     * deleted milestone drop out of the milestone join, same as in the project view.
     */
    @Query(nativeQuery = true, value = """
            WITH RECURSIVE tree AS (
                SELECT p.project_id AS root_id, p.project_id AS project_id
                FROM funding_project p
                WHERE p.organisation_id = :organisationId AND p.parent_project_id IS NULL
                UNION ALL
                SELECT t.root_id, c.project_id
                FROM funding_project c
                JOIN tree t ON c.parent_project_id = t.project_id
            ),
            root_amounts AS (
                SELECT t.root_id,
                       COALESCE(SUM(CASE WHEN e.event_type = 'SPENDING' THEN a.allocated_amount END), 0) AS spent,
                       COALESCE(SUM(CASE WHEN e.event_type = 'FUNDING' THEN a.allocated_amount
                                         WHEN e.event_type = 'REFUND' THEN -a.allocated_amount END), 0) AS funded
                FROM tree t
                LEFT JOIN funding_milestone m ON m.project_id = t.project_id
                LEFT JOIN funding_event_milestone_allocation a ON a.milestone_id = m.milestone_id
                LEFT JOIN funding_event e ON e.event_id = a.event_id
                GROUP BY t.root_id
            )
            SELECT r.project_id
            FROM funding_project r
            JOIN root_amounts ra ON ra.root_id = r.project_id
            WHERE (CAST(:search AS text) IS NULL
                   OR EXISTS (
                       SELECT 1 FROM tree t
                       JOIN funding_project tp ON tp.project_id = t.project_id
                       WHERE t.root_id = r.project_id
                       AND LOWER(tp.project_title) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))
                   OR EXISTS (
                       SELECT 1 FROM tree t
                       JOIN funding_milestone m ON m.project_id = t.project_id
                       WHERE t.root_id = r.project_id
                       AND LOWER(m.milestone_title) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))))
            AND (CAST(:minAmount AS numeric) IS NULL OR CASE CAST(:amountTarget AS text)
                    WHEN 'FUNDED' THEN ra.funded WHEN 'SPENT' THEN ra.spent ELSE r.total_amount END >= CAST(:minAmount AS numeric))
            AND (CAST(:maxAmount AS numeric) IS NULL OR CASE CAST(:amountTarget AS text)
                    WHEN 'FUNDED' THEN ra.funded WHEN 'SPENT' THEN ra.spent ELSE r.total_amount END <= CAST(:maxAmount AS numeric))
            """)
    List<String> findRootProjectIdsByStructureFilter(
            @Param("organisationId") String organisationId,
            @Param("search") String search,
            @Param("amountTarget") String amountTarget,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount);

    /**
     * Paged root projects of the organisation, optionally restricted to the given ids (the result of
     * {@link #findRootProjectIdsByStructureFilter}) and to the given lower-cased currencies; a {@code null}
     * argument disables its filter. Pagination and sorting run here, on the entity, as for the unfiltered list.
     */
    @Query("""
            SELECT p FROM funding.ProjectEntity p
            WHERE p.organisationId = :organisationId
            AND p.parentProject IS NULL
            AND (:ids IS NULL OR p.id IN :ids)
            AND (:currencies IS NULL OR LOWER(p.currency) IN :currencies)
            """)
    Page<ProjectEntity> findRootsByOrganisationIdAndFilter(
            @Param("organisationId") String organisationId,
            @Param("ids") Collection<String> ids,
            @Param("currencies") List<String> currencies,
            Pageable pageable);

    List<ProjectEntity> findByParentProjectIdIn(Collection<String> parentProjectIds);

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
