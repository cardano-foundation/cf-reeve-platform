package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingItemEntity;

public interface FundingItemRepository extends JpaRepository<FundingItemEntity, String> {

    List<FundingItemEntity> findByEvent_Id(String eventId);

}
