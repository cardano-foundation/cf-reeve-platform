package org.cardanofoundation.lob.app.funding.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

public interface EventMilestoneAllocationRepository extends JpaRepository<EventMilestoneAllocationEntity, EventMilestoneAllocationEntity.Id> {

    List<EventMilestoneAllocationEntity> findById_EventId(String eventId);

    List<EventMilestoneAllocationEntity> findById_MilestoneIdIn(Collection<String> milestoneIds);

    /**
     * Just the distinct event ids allocated to any of the given milestones — a scalar projection, so no
     * {@code EventMilestoneAllocationEntity} is loaded into the persistence context. That matters when the
     * caller is about to delete those milestones in the same session: an allocation entity left managed
     * here would still reference a milestone that's about to be removed, and the next auto-flush reports
     * that as a spurious {@code TransientObjectException} (reproduced in {@code ProjectTreeUpdateE2ETest}).
     */
    @Query("""
            SELECT DISTINCT a.id.eventId FROM funding.EventMilestoneAllocationEntity a
            WHERE a.id.milestoneId IN :milestoneIds
            """)
    List<String> findEventIdsByMilestoneIdIn(@Param("milestoneIds") Collection<String> milestoneIds);

    boolean existsByMilestoneIdAndEventStatus(String milestoneId, EventStatus status);

    boolean existsByMilestoneProjectIdAndEventStatus(String projectId, EventStatus status);

    /** Whether any of the given milestones is allocated by an event in the given status. */
    @Query("""
            SELECT COUNT(a) > 0 FROM funding.EventMilestoneAllocationEntity a
            WHERE a.id.milestoneId IN :milestoneIds AND a.event.status = :status
            """)
    boolean existsByMilestoneIdInAndEventStatus(@Param("milestoneIds") Collection<String> milestoneIds, @Param("status") EventStatus status);

    /**
     * Whether any milestone owned by one of the given projects is allocated by an event in the given
     * status. Used to lock a project when it (or a descendant sub-project) is tied to a published event.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM funding.EventMilestoneAllocationEntity a
            WHERE a.milestone.project.id IN :projectIds AND a.event.status = :status
            """)
    boolean existsByMilestoneProjectIdInAndEventStatus(@Param("projectIds") Collection<String> projectIds, @Param("status") EventStatus status);

    /** Total amount allocated to a milestone across all events (null allocations ignored, no rows → 0). */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM funding.EventMilestoneAllocationEntity a
            WHERE a.id.milestoneId = :milestoneId
            """)
    BigDecimal sumAllocatedByMilestoneId(@Param("milestoneId") String milestoneId);

    /** Spent amount for a milestone: sum of SPENDING allocations only (FUNDING and REFUND excluded). */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM funding.EventMilestoneAllocationEntity a
            WHERE a.id.milestoneId = :milestoneId AND a.event.eventType = :spending
            """)
    BigDecimal spentAmountByMilestoneId(@Param("milestoneId") String milestoneId,
                                        @Param("spending") EventType spending);

    /**
     * Spent amount directly against a project's own milestones (sub-projects are not rolled up here —
     * a project holds either milestones or sub-projects, never both, so this is exact for a leaf
     * project): sum of SPENDING allocations only.
     */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM funding.EventMilestoneAllocationEntity a
            WHERE a.milestone.project.id = :projectId AND a.event.eventType = :spending
            """)
    BigDecimal spentAmountByProjectId(@Param("projectId") String projectId,
                                       @Param("spending") EventType spending);

}
