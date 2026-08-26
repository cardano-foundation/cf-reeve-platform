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

    /**
     * Whether any milestone owned by one of the given projects is allocated by any event, regardless
     * of status (draft or published). Used to block a currency change: a currency edit changes what
     * an already-recorded amount means, so it must be rejected once any funding/spending has been
     * allocated — not just once an event has been published.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM funding.EventMilestoneAllocationEntity a
            WHERE a.milestone.project.id IN :projectIds
            """)
    boolean existsByMilestoneProjectIdIn(@Param("projectIds") Collection<String> projectIds);

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

}
