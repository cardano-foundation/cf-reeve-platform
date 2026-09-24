package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

public interface FundingEventRepository extends JpaRepository<FundingEventEntity, String> {

    Page<FundingEventEntity> findByOrganisationId(String organisationId, Pageable pageable);

    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.organisationId = :organisationId
            AND (:status IS NULL OR e.status = :status)
            AND (:eventType IS NULL OR e.eventType = :eventType)
            """)
    Page<FundingEventEntity> findByOrganisationIdAndFilter(
            @Param("organisationId") String organisationId,
            @Param("status") EventStatus status,
            @Param("eventType") EventType eventType,
            Pageable pageable);

    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE EXISTS (
                SELECT a FROM funding.EventMilestoneAllocationEntity a
                WHERE a.event = e AND a.milestone.project.id = :projectId
            )
            AND (:status IS NULL OR e.status = :status)
            AND (:eventType IS NULL OR e.eventType = :eventType)
            """)
    Page<FundingEventEntity> findByProjectIdAndFilter(
            @Param("projectId") String projectId,
            @Param("status") EventStatus status,
            @Param("eventType") EventType eventType,
            Pageable pageable);

    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.ledgerDispatchApproved IS TRUE
            AND e.ledgerDispatchStatus = 'NOT_DISPATCHED'
            AND e.organisationId = :organisationId
            """)
    Set<FundingEventEntity> findAllToBePublished(@Param("organisationId") String organisationId);

    /**
     * ERROR events for this organisation with no milestone allocation left at all — every one was
     * removed by an earlier project/milestone cascade delete (LOB-2365 follow-up), so there is nothing
     * left on them to reconcile. Used by the bulk orphan-cleanup endpoint; deliberately excludes an
     * ERROR event that still has at least one real allocation left, since that one may still hold data
     * worth a human fixing rather than discarding.
     */
    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.organisationId = :organisationId AND e.status = :status
            AND NOT EXISTS (SELECT a FROM funding.EventMilestoneAllocationEntity a WHERE a.event = e)
            """)
    List<FundingEventEntity> findOrphanedEvents(@Param("organisationId") String organisationId, @Param("status") EventStatus status);

}
