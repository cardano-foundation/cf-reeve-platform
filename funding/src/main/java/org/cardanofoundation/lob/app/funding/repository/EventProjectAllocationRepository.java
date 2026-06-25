package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.EventProjectAllocationEntity;

public interface EventProjectAllocationRepository extends JpaRepository<EventProjectAllocationEntity, EventProjectAllocationEntity.Id> {

    List<EventProjectAllocationEntity> findById_EventId(String eventId);

    void deleteById_EventId(String eventId);

}
