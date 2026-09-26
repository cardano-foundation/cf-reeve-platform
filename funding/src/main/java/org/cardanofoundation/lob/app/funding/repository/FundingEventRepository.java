package org.cardanofoundation.lob.app.funding.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

public interface FundingEventRepository extends JpaRepository<FundingEventEntity, String> {

    Page<FundingEventEntity> findByOrganisationId(String organisationId, Pageable pageable);

    /**
     * Row-locked single-event read, used only by {@code SpendingEventService#publish} — without this
     * lock, publish's read-status/flip-to-PUBLISHED and {@code FundingCascadeDeleteService
     * #flagEventsAllocatedTo}'s read-status/flip-to-ERROR race on the same row: two concurrent
     * transactions can each read the pre-change status before either commits, so one write silently
     * overwrites the other regardless of which "should" win. Locking here makes publish() wait for any
     * concurrent flag-to-ERROR transaction (and vice versa — see {@code findAllByIdForUpdate}) to finish
     * first, so it always sees that transaction's committed result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM funding.FundingEventEntity e WHERE e.id = :id")
    Optional<FundingEventEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Row-locked multi-event read, used only by {@code FundingCascadeDeleteService
     * #flagEventsAllocatedTo} — see {@link #findByIdForUpdate}'s Javadoc for why the lock exists. Pass
     * ids sorted into a stable order so two calls that lock overlapping sets of events (e.g. two
     * structural edits touching the same event from different milestones) always acquire their locks in
     * the same order and can never deadlock on each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM funding.FundingEventEntity e WHERE e.id IN :ids")
    List<FundingEventEntity> findAllByIdForUpdate(@Param("ids") Collection<String> ids);

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
     * ERROR events for this organisation none of whose allocations points at an existing milestone —
     * every milestone they referenced was removed by an earlier project/milestone cascade delete
     * (LOB-2365 follow-up), which deliberately keeps the allocation rows (dangling), so "no allocation
     * rows" is never the test. Used by the bulk orphan-cleanup endpoint; deliberately excludes an ERROR
     * event that still has at least one allocation to an existing milestone, since that one can still be
     * fixed by a human rather than discarded.
     */
    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.organisationId = :organisationId AND e.status = :status
            AND NOT EXISTS (
                SELECT a FROM funding.EventMilestoneAllocationEntity a
                WHERE a.event = e
                AND EXISTS (SELECT m FROM funding.MilestoneEntity m WHERE m.id = a.id.milestoneId))
            """)
    List<FundingEventEntity> findOrphanedEvents(@Param("organisationId") String organisationId, @Param("status") EventStatus status);

}
