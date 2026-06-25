package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.EventMilestoneAllocationEntity;

public interface EventMilestoneAllocationRepository extends JpaRepository<EventMilestoneAllocationEntity, EventMilestoneAllocationEntity.Id> {

    List<EventMilestoneAllocationEntity> findById_EventId(String eventId);

    List<EventMilestoneAllocationEntity> findById_EventIdAndId_ProjectId(String eventId, String projectId);

    void deleteById_EventId(String eventId);

}
