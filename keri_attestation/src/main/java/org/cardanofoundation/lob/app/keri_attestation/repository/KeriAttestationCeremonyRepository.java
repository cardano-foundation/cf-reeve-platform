package org.cardanofoundation.lob.app.keri_attestation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;

public interface KeriAttestationCeremonyRepository extends JpaRepository<KeriAttestationCeremonyEntity, String> {

    /**
     * Per-user active-ceremony count, used to enforce {@code limits.max-active-ceremonies-per-user}.
     * {@code terminal} is the set of states that no longer count against the limit
     * ({@code CONSUMED}, {@code FAILED}, {@code EXPIRED}).
     */
    long countByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);

    /** Same scope as {@link #countByUserIdAndStateNotIn}, returning the rows themselves — e.g. to
     *  invalidate a user's open ceremonies on relink. */
    List<KeriAttestationCeremonyEntity> findByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);

    /**
     * Row-level {@code SELECT ... FOR UPDATE}. Every step transition CASes on {@code (state,
     * attemptGeneration)} — the lock serializes a retry bumping the generation against
     * a late async completion reading and applying the pre-bump state, mirroring
     * {@code VaultDocumentRepository#findByIdForUpdate} in document_vault.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from keri_attestation.KeriAttestationCeremonyEntity c where c.id = :id")
    Optional<KeriAttestationCeremonyEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Candidates for {@code CeremonyCleanupJob}'s lazy-expiry sweep: still open, but past their TTL.
     * Unlocked — {@code CeremonyCleanupJob} re-checks each candidate under {@link #findByIdForUpdate}
     * before writing, so this discovery read racing a legitimate concurrent transition never causes a
     * lost update.
     */
    List<KeriAttestationCeremonyEntity> findByStateNotInAndExpiresAtBefore(
            Collection<CeremonyState> terminal, LocalDateTime cutoff);

    /**
     * Candidates for {@code CeremonyCleanupJob}'s step-level stale-detection sweep: ceremonies
     * sitting in one of the WAITING states whose {@code updatedAt} is older than a
     * broad discovery cutoff. Unlocked for the same reason as {@link #findByStateNotInAndExpiresAtBefore}
     * — the sweep re-verifies each candidate (including its precise, per-state timeout) under
     * {@link #findByIdForUpdate} before writing.
     */
    List<KeriAttestationCeremonyEntity> findByStateInAndUpdatedAtBefore(
            Collection<CeremonyState> waiting, LocalDateTime cutoff);

    /**
     * Purges terminal rows older than {@code CeremonyCleanupJob}'s retention window. A single bulk
     * JPQL delete rather than a Spring Data derived delete — the derived form SELECTs every matching
     * row into the persistence context and removes them one-by-one inside one long transaction, which
     * is slow and lock-heavy against a real backlog (mirrors
     * {@code VaultDocumentRepository#deleteByStatusAndCreatedAtBefore} in document_vault).
     */
    @Modifying
    @Query("delete from keri_attestation.KeriAttestationCeremonyEntity c "
            + "where c.state in :terminal and c.updatedAt < :cutoff")
    long deleteByStateInAndUpdatedAtBefore(@Param("terminal") Collection<CeremonyState> terminal,
                                            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Backs {@link org.cardanofoundation.lob.app.keri_attestation.service.AttestationConsumptionApi
     * #findTerminalNonConsumedCeremonyIds}: of {@code ids}, the ones currently in one of
     * {@code states} (the caller passes {@code FAILED}/{@code EXPIRED}). Selects only the id column —
     * a target provider's freeze-cleanup sweep needs nothing else from this row.
     */
    @Query("select c.id from keri_attestation.KeriAttestationCeremonyEntity c "
            + "where c.id in :ids and c.state in :states")
    List<String> findIdsByIdInAndStateIn(@Param("ids") Collection<String> ids,
                                          @Param("states") Collection<CeremonyState> states);
}
