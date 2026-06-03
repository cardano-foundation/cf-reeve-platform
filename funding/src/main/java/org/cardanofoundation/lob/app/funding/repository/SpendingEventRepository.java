package org.cardanofoundation.lob.app.funding.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.SpendingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

public interface SpendingEventRepository extends JpaRepository<SpendingEventEntity, String> {

    List<SpendingEventEntity> findByProject_Id(String projectId);

    Page<SpendingEventEntity> findByProject_Id(String projectId, Pageable pageable);

    @Query("""
            SELECT e FROM funding.SpendingEventEntity e
            WHERE e.project.id = :projectId
            AND (:status IS NULL OR e.status = :status)
            AND (:eventType IS NULL OR e.eventType = :eventType)
            """)
    Page<SpendingEventEntity> findByProjectIdAndFilter(
            @Param("projectId") String projectId,
            @Param("status") EventStatus status,
            @Param("eventType") EventType eventType,
            Pageable pageable);

    @Query("""
            SELECT e FROM funding.SpendingEventEntity e
            WHERE e.project.organisationId = :organisationId
            AND (:fundingId IS NULL OR e.fundingId = :fundingId)
            """)
    Page<SpendingEventEntity> findByOrganisationIdAndFundingId(
            @Param("organisationId") String organisationId,
            @Param("fundingId") String fundingId,
            Pageable pageable);

}
