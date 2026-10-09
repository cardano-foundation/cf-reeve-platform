package org.cardanofoundation.lob.app.document_vault.repository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.blockchain_common.domain.LedgerDispatchStatus;
import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;
import org.cardanofoundation.lob.app.document_vault.domain.enums.VaultDocumentStatus;

public interface VaultDocumentRepository extends JpaRepository<VaultDocumentEntity, String> {

    /**
     * Takes a row-level {@code SELECT ... FOR UPDATE}, used by both mutating paths on this aggregate
     * so publish and delete serialize against each other: neither can observe {@code DRAFT} while the
     * other holds the lock and then act on that stale read. The loser blocks, re-reads
     * {@code PUBLISHED} and returns {@code ALREADY_PUBLISHED} or
     * {@code DOCUMENT_PUBLISHED_IMMUTABLE}. Read-only fetch and list use the unlocked finder.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from document_vault.VaultDocumentEntity d where d.id = :documentId")
    Optional<VaultDocumentEntity> findByIdForUpdate(@Param("documentId") String documentId);

    /**
     * Retention sweep: hard-deletes envelopes matching {@code status} created before {@code cutoff}.
     * Callers must pass {@link VaultDocumentStatus#DRAFT} only — published envelopes are locked
     * forever regardless of age.
     *
     * <p>A bulk JPQL delete rather than a derived one, which would load every matching row into the
     * persistence context and remove them one by one.
     */
    @Modifying
    @Query("delete from document_vault.VaultDocumentEntity d where d.status = :status and d.createdAt < :cutoff")
    long deleteByStatusAndCreatedAtBefore(@Param("status") VaultDocumentStatus status,
                                           @Param("cutoff") LocalDateTime cutoff);

    /**
     * Documents whose publish committed but whose {@code DocumentPublishCommand} may never have
     * reached blockchain_publisher. Feeds {@code DocumentDispatchRetryJob}; see that class for the
     * cursor ordering below and why re-emitting is safe.
     *
     * <p>{@code pageable} bounds the sweep so a backlog drains across ticks. It is passed unsorted:
     * the {@code order by} is expressed in JPQL because NULLS FIRST must apply to only one of the two
     * sort keys.
     */
    @Query("select d from document_vault.VaultDocumentEntity d "
            + "where d.status = :status and d.ledgerDispatchStatus = :ledgerDispatchStatus "
            + "order by d.dispatchRetryAt asc nulls first, d.publishedAt asc")
    List<VaultDocumentEntity> findByStatusAndLedgerDispatchStatus(@Param("status") VaultDocumentStatus status,
            @Param("ledgerDispatchStatus") LedgerDispatchStatus ledgerDispatchStatus,
            Pageable pageable);

    /**
     * Org-wide listing with optional (nullable) filters. {@code direction} is a String
     * ('SENT'/'RECEIVED') to keep the null-check portable; {@code status} is typed.
     *
     * <p>{@code :q} arrives pre-escaped from the service layer, and {@code escape '\\'} makes the LIKE
     * operator treat a backslash-prefixed {@code %} or {@code _} as a literal. Without it a query of
     * just {@code %} would match every document in the organisation.
     */
    String SEARCH_WHERE = "where d.organisationId = :organisationId "
            + "and (:status is null or d.status = :status) "
            + "and (:q is null or lower(d.fileName) like lower(concat('%', cast(:q as string), '%')) escape '\\' "
            + "     or lower(d.description) like lower(concat('%', cast(:q as string), '%')) escape '\\') "
            + "and (:direction is null "
            + "     or (:direction = 'SENT' and d.createdByAccount = :accountId) "
            + "     or (:direction = 'RECEIVED' and exists ("
            + "         select 1 from document_vault.VaultKeyEntity k, in(d.slots) s "
            + "         where s.keyId = k.id and k.accountId = :accountId)))";

    // Explicit countQuery: Spring Data cannot reliably derive a count over the exists-subquery form.
    @Query(value = "select d from document_vault.VaultDocumentEntity d " + SEARCH_WHERE,
           countQuery = "select count(d) from document_vault.VaultDocumentEntity d " + SEARCH_WHERE)
    Page<VaultDocumentEntity> search(@Param("organisationId") String organisationId,
                                     @Param("accountId") String accountId,
                                     @Param("direction") String direction,
                                     @Param("status") VaultDocumentStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);
}
