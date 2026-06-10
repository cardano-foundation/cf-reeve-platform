package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.SpendingItemEntity;

public interface SpendingItemRepository extends JpaRepository<SpendingItemEntity, String> {

    List<SpendingItemEntity> findByEvent_Id(String eventId);

}
