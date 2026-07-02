package org.cardanofoundation.lob.app.funding.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;

public interface EventMilestoneAllocationRepository extends JpaRepository<EventMilestoneAllocationEntity, EventMilestoneAllocationEntity.Id> {

    List<EventMilestoneAllocationEntity> findById_EventId(String eventId);

    boolean existsByMilestoneIdAndEventStatus(String milestoneId, EventStatus status);

    boolean existsByMilestoneProjectIdAndEventStatus(String projectId, EventStatus status);

    /** Total amount allocated to a milestone across all events (null allocations ignored, no rows → 0). */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM funding.EventMilestoneAllocationEntity a
            WHERE a.id.milestoneId = :milestoneId
            """)
    BigDecimal sumAllocatedByMilestoneId(@Param("milestoneId") String milestoneId);

}
