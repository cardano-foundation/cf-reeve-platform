package org.cardanofoundation.lob.app.keri_attestation.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.cardanofoundation.lob.app.keri_attestation.domain.entity.KeriIdentityLinkEntity;

public interface KeriIdentityLinkRepository extends JpaRepository<KeriIdentityLinkEntity, String> {

    /**
     * Row-level {@code SELECT ... FOR UPDATE} on a user's identity link (F3 fix, design §4.7).
     * {@code KeriOobiService}'s relink path and the two async {@code persist*IfIdentityStillCurrent}
     * mutators ({@code KeriCredentialService}, {@code KeriAuthBeginService}) all read-then-write this
     * row — an async credential/auth-begin write racing an unlocked relink read could otherwise land in
     * either order and produce a row that is a mix of the old and new identity (e.g. the new
     * {@code aid}/{@code oobiUrl} with a credential that belongs to the AID being relinked away from).
     * Every write decision on this entity goes through this locked read instead of
     * {@link #findById(Object)}; mirrors
     * {@code KeriAttestationCeremonyRepository#findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from keri_attestation.KeriIdentityLinkEntity l where l.userId = :userId")
    Optional<KeriIdentityLinkEntity> findByUserIdForUpdate(@Param("userId") String userId);
}
