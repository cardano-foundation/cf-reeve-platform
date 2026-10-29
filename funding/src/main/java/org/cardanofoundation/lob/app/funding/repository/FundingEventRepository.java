package org.cardanofoundation.lob.app.funding.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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
     * Row-locked read used by publish: a concurrent publish of the same event (same or another request)
     * blocks here until the first one commits, then sees it PUBLISHED — so an event can never be
     * published twice, nor have its dispatch status reset by a stale write after the publish job ran.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM funding.FundingEventEntity e WHERE e.id = :id")
    Optional<FundingEventEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Organisation events list with optional, AND-combined filters; a {@code null} argument disables its
     * filter. Project title matching is an {@code EXISTS} over the event's milestone allocations (matching
     * the milestone's project or, for a sub-project, its parent) so an event allocated to several matching
     * projects is returned once and the page count stays correct. Currency matches either the reporting or
     * the foreign (spend) currency.
     */
    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.organisationId = :organisationId
            AND (:statuses IS NULL OR e.status IN :statuses)
            AND (:eventTypes IS NULL OR e.eventType IN :eventTypes)
            AND (:currency IS NULL OR LOWER(e.currencyRcy) = LOWER(CAST(:currency AS string))
                 OR LOWER(e.currencyFcy) = LOWER(CAST(:currency AS string)))
            AND (CAST(:dateFrom AS LocalDate) IS NULL OR e.eventDate >= :dateFrom)
            AND (CAST(:dateTo AS LocalDate) IS NULL OR e.eventDate <= :dateTo)
            AND (CAST(:minAmount AS BigDecimal) IS NULL OR e.totalAmount >= :minAmount)
            AND (CAST(:maxAmount AS BigDecimal) IS NULL OR e.totalAmount <= :maxAmount)
            AND (:projectTitle IS NULL OR EXISTS (
                SELECT a FROM funding.EventMilestoneAllocationEntity a
                JOIN a.milestone m
                JOIN m.project p
                LEFT JOIN p.parentProject pp
                WHERE a.event = e
                AND (LOWER(p.projectTitle) LIKE LOWER(CONCAT('%', CAST(:projectTitle AS string), '%'))
                     OR LOWER(pp.projectTitle) LIKE LOWER(CONCAT('%', CAST(:projectTitle AS string), '%')))
            ))
            """)
    Page<FundingEventEntity> findByOrganisationIdAndFilter(
            @Param("organisationId") String organisationId,
            @Param("statuses") List<EventStatus> statuses,
            @Param("eventTypes") List<EventType> eventTypes,
            @Param("projectTitle") String projectTitle,
            @Param("currency") String currency,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
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
