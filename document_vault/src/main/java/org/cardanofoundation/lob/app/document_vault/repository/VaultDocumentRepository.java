package org.cardanofoundation.lob.app.document_vault.repository;


import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     */
    long deleteByStatusAndCreatedAtBefore(VaultDocumentStatus status, LocalDateTime cutoff);

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
