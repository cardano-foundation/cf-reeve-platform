package org.cardanofoundation.lob.app.funding.repository;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.funding.domain.entity.FundingEventEntity;
import org.cardanofoundation.lob.app.funding.domain.enums.EventStatus;
import org.cardanofoundation.lob.app.funding.domain.enums.EventType;

public interface FundingEventRepository extends JpaRepository<FundingEventEntity, String> {

    Page<FundingEventEntity> findByOrganisationId(String organisationId, Pageable pageable);

    /**
     * Whether another event (any id but {@code excludeEventId}) already uses this Funding ID for
     * this organisation and event type — backs the create/update Funding ID uniqueness pre-check
     * (see {@code SpendingEventService#fundingEventIdAvailable}), which only ever calls this with
     * {@code eventType = FUNDING}: a SPENDING/REFUND event is expected to reuse a FUNDING event's
     * Funding ID. {@code excludeEventId} lets an update exclude the event's own row; on create it is
     * the event's not-yet-persisted id, so it can never match and nothing is excluded.
     */
    boolean existsByOrganisationIdAndEventTypeAndFundingIdAndIdNot(
            String organisationId, EventType eventType, String fundingId, String excludeEventId);

    @Query("""
            SELECT e FROM funding.FundingEventEntity e
            WHERE e.organisationId = :organisationId
            AND (:status IS NULL OR e.status = :status)
            AND (:eventType IS NULL OR e.eventType = :eventType)
            """)
    Page<FundingEventEntity> findByOrganisationIdAndFilter(
            @Param("organisationId") String organisationId,
            @Param("status") EventStatus status,
            @Param("eventType") EventType eventType,
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

}
