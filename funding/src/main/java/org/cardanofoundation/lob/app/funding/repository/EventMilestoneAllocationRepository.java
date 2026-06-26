package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;

public interface EventMilestoneAllocationRepository extends JpaRepository<EventMilestoneAllocationEntity, EventMilestoneAllocationEntity.Id> {

    List<EventMilestoneAllocationEntity> findById_EventId(String eventId);

    boolean existsByMilestone_IdAndEvent_Status(String milestoneId, EventStatus status);

    boolean existsByMilestone_Project_IdAndEvent_Status(String projectId, EventStatus status);

}
