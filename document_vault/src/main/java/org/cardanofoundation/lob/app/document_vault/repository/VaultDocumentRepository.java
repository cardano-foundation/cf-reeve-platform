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
     * Takes a row-level {@code SELECT ... FOR UPDATE}. Used by BOTH mutating paths on this
     * aggregate — publish and delete — so they serialize against each other on the same row lock:
     * <ul>
     *   <li>publish: two concurrent publish calls cannot both observe {@code DRAFT} and both fire
     *       the irreversible {@code DocumentPublishCommand}; the second blocks until the first
     *       commits, then reads {@code PUBLISHED} and returns {@code ALREADY_PUBLISHED}.</li>
     *   <li>delete: a concurrent delete cannot observe {@code DRAFT} while a publish is in flight
     *       and then unconditionally delete the row after publish commits it to {@code PUBLISHED};
     *       delete blocks until publish commits, re-reads {@code PUBLISHED}, and returns
     *       {@code DOCUMENT_PUBLISHED_IMMUTABLE} instead.</li>
     * </ul>
     * fetch/list stay on the unlocked finder — they are read-only and never mutate status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from document_vault.VaultDocumentEntity d where d.id = :documentId")
    Optional<VaultDocumentEntity> findByIdForUpdate(@Param("documentId") String documentId);

    /**
     * Blueprint B3 retention: hard-deletes envelopes matching {@code status} created before
     * {@code cutoff}. Callers must pass {@link VaultDocumentStatus#DRAFT} only — PUBLISHED
     * envelopes are locked forever regardless of age (settled product decision).
     *
     * <p>Single bulk JPQL delete rather than a Spring Data derived delete — the derived form
     * SELECTs every matching row into the persistence context and removes them one-by-one inside
     * one long transaction, which is slow and lock-heavy against a real backlog.
     *
     * <p><b>Slot cleanup relies on the database, not JPA:</b> {@link VaultDocumentEntity#getSlots()}
     * is an {@code @ElementCollection} backed by {@code document_vault_document_slot}. A bulk JPQL
     * delete does NOT cascade to element-collection tables — Hibernate never sees the parent rows
     * being removed, so it can't fire its usual orphan cleanup. This is safe here because the
     * {@code document_vault_document_slot.document_id} FK is declared {@code ON DELETE CASCADE}
     * (see V1.6_100_13__lob_service_app_document_vault_module.sql), so PostgreSQL removes the slot
     * rows itself when the parent document row is deleted. If that FK ever loses its cascade, this
     * bulk delete would silently orphan slot rows.
     */
    @Modifying
    @Query("delete from document_vault.VaultDocumentEntity d where d.status = :status and d.createdAt < :cutoff")
    long deleteByStatusAndCreatedAtBefore(@Param("status") VaultDocumentStatus status,
                                           @Param("cutoff") LocalDateTime cutoff);

    /**
     * Documents whose {@code publish()} committed {@code PUBLISHED}/{@code MARK_DISPATCH} but whose
     * {@code DocumentPublishCommand} may never have reached blockchain_publisher — a crash or async
     * executor rejection between the vault commit and the in-memory event landing (Codex
     * adversarial-review finding 1). Feeds {@code DocumentDispatchRetryJob}, which re-emits the
     * command for each match on a schedule. Re-emitting for a document that is legitimately still
     * mid-flight (the first command has not landed yet) is safe and a no-op downstream — see
     * {@code DocumentEntityRepositoryGateway#storeOnlyNew}, which dedups by documentId.
     *
     * <p>Bounded by {@code pageable} (Codex adversarial-review finding 2 of round 2): an unbounded
     * sweep would materialize every stuck document's ciphertext in one go against a large backlog.
     * {@code DocumentDispatchRetryJob} passes a fixed-size page so a backlog is drained across ticks
     * instead of all at once.
     *
     * <p><b>Retry-cursor ordering (Codex adversarial-review finding, round 3 — retry-sweep
     * starvation):</b> ordered by {@code dispatchRetryAt} ascending with NULLS FIRST, then {@code
     * publishedAt} ascending. {@code PUBLISHED}/{@code MARK_DISPATCH} is a legitimate resting state
     * while downstream dispatch is still in progress, not just a crash symptom — a row's ledger
     * status can sit at {@code MARK_DISPATCH} for a long time without anything being wrong. Ordering
     * purely by {@code publishedAt} would let the same oldest rows monopolize every sweep forever,
     * starving younger stuck documents whose in-memory handoff was actually lost. {@code
     * DocumentDispatchRetryJob} stamps {@code dispatchRetryAt} on every row it (re-)attempts, so the
     * NULLS-FIRST ordering always tries never-yet-attempted documents first and rotates already
     * attempted rows to the back — fairness comes from this cursor, never from waiting on {@link
     * VaultDocumentEntity#getLedgerDispatchStatus()} to advance.
     *
     * <p>Explicit JPQL {@code order by} rather than a {@link org.springframework.data.domain.Sort}
     * folded into {@code pageable}: NULLS FIRST must apply to exactly one of the two sort keys,
     * which a derived Spring Data query method cannot express as cleanly. The caller passes an
     * unsorted {@code Pageable} purely for the offset/limit bound.
     */
    @Query("select d from document_vault.VaultDocumentEntity d "
            + "where d.status = :status and d.ledgerDispatchStatus = :ledgerDispatchStatus "
            + "order by d.dispatchRetryAt asc nulls first, d.publishedAt asc")
    List<VaultDocumentEntity> findByStatusAndLedgerDispatchStatus(@Param("status") VaultDocumentStatus status,
            @Param("ledgerDispatchStatus") LedgerDispatchStatus ledgerDispatchStatus,
            Pageable pageable);

    /**
     * Org-wide listing with optional filters (all nullable) — direction is a String ('SENT'/'RECEIVED')
     * to keep the null-check portable; status is typed. Sorting/paging via Pageable.
     *
     * {@code :q} arrives here PRE-ESCAPED by the service layer (LIKE metacharacters {@code \\}, {@code %}
     * and {@code _} are backslash-escaped before binding) — {@code escape '\\'} tells the LIKE operator
     * to treat a backslash-prefixed {@code %}/{@code _} as a literal character rather than a wildcard.
     * Without this, a fileName/description containing a literal {@code %} or {@code _} would either fail
     * to match, or — worse — a query of just {@code %} would match every document in the organisation.
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

    // explicit countQuery: don't rely on Spring Data deriving a count over the exists-subquery form
    @Query(value = "select d from document_vault.VaultDocumentEntity d " + SEARCH_WHERE,
           countQuery = "select count(d) from document_vault.VaultDocumentEntity d " + SEARCH_WHERE)
    Page<VaultDocumentEntity> search(@Param("organisationId") String organisationId,
                                     @Param("accountId") String accountId,
                                     @Param("direction") String direction,
                                     @Param("status") VaultDocumentStatus status,
                                     @Param("q") String q,
                                     Pageable pageable);
}
