package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Collection;
import java.util.List;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;

/**
 * The only surface a target-owning module (e.g. document_vault) is allowed to depend on: exchanging
 * a completed ceremony for the attestation it produced. Deliberately narrow — everything else about
 * ceremony orchestration ({@link CeremonyService}'s full API) is an implementation detail of this
 * module (design §4.6).
 */
public interface AttestationConsumptionApi {

    Either<ProblemDetail, ConsumedAttestation> validateAndConsume(
            String ceremonyId, String targetType, String targetId, String userId);

    /**
     * Read-only: of the given ceremony ids, returns those currently in a terminal, non-{@code
     * CONSUMED} state ({@code FAILED} or {@code EXPIRED}) — i.e. any freeze row a target provider
     * (e.g. {@code blockchain_publisher}'s {@code DocumentAttestationFreezeCleanupJob}, Task 13)
     * holds for one of them can never be dispatched and is safe to delete.
     *
     * <p>A ceremony id absent from the returned list is NOT necessarily still open — it may also be
     * {@code CONSUMED} (must never be deleted: {@link org.cardanofoundation.lob.app.keri_attestation.job.CeremonyCleanupJob}'s own retention sweep keeps
     * those rows forever precisely so a late dispatch retry can still read them) or already purged by
     * that same sweep (which only ever purges {@code FAILED}/{@code EXPIRED} rows, never {@code
     * CONSUMED} — see its javadoc). Callers must treat "absent" as "keep", never as "safe to delete":
     * a caller unable to distinguish a purged-FAILED ceremony from one it simply doesn't know about
     * anymore has no way to prove it was never CONSUMED, and deleting a live attestation's freeze row
     * is the one mistake this API must never enable.
     */
    List<String> findTerminalNonConsumedCeremonyIds(Collection<String> ceremonyIds);

}
