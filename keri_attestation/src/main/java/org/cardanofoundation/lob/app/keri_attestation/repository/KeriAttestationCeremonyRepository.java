package org.cardanofoundation.lob.app.keri_attestation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.CeremonyState;
import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriAttestationCeremonyEntity;

public interface KeriAttestationCeremonyRepository extends JpaRepository<KeriAttestationCeremonyEntity, String> {

    /**
     * Per-user active-ceremony count, used to enforce {@code limits.max-active-ceremonies-per-user}
     * (design §3.2) — {@code terminal} is the set of states that no longer count against the limit
     * ({@code CONSUMED}, {@code FAILED}, {@code EXPIRED}).
     */
    long countByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);

    /** Same scope as {@link #countByUserIdAndStateNotIn}, returning the rows themselves — e.g. to
     *  invalidate a user's open ceremonies on relink (§4.7). */
    List<KeriAttestationCeremonyEntity> findByUserIdAndStateNotIn(String userId, Collection<CeremonyState> terminal);

    /**
     * Row-level {@code SELECT ... FOR UPDATE}. Every step transition CASes on {@code (state,
     * attemptGeneration)} (design §4.2) — the lock serializes a retry bumping the generation against
     * a late async completion reading and applying the pre-bump state, mirroring
     * {@code VaultDocumentRepository#findByIdForUpdate} in document_vault.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from keri_attestation.KeriAttestationCeremonyEntity c where c.id = :id")
    Optional<KeriAttestationCeremonyEntity> findByIdForUpdate(@Param("id") String id);
}
