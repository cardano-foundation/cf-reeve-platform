package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;

/**
 * The only surface a target-owning module (e.g. document_vault) is allowed to depend on: exchanging
 * a completed ceremony for the attestation it produced. Deliberately narrow — everything else about
 * ceremony orchestration ({@link CeremonyService}'s full API) is an implementation detail of this
 * module.
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

    /**
     * Read-only re-lookup of a ceremony's {@link ConsumedAttestation} for a caller that already knows
     * it was consumed: {@code blockchain_publisher}'s dispatch hook needs the AID /
     * digest / KEL sequence a document's frozen ceremony id attested, well after {@link
     * #validateAndConsume} already ran — that method returns its result once, at publish time, and
     * cannot be called again (it CASes the ceremony to {@code CONSUMED} and rejects any state other
     * than {@code ATTEST_ANCHORED} on a second call).
     *
     * <p>Deliberately performs NO ownership/authorization check, unlike every other method on this
     * interface: dispatch is a system path (a scheduled job re-reading its own persisted dispatch
     * record), not an authenticated user request, so there is no caller identity to check against —
     * the ceremony id alone is the only input, and it was already authorized once, by {@link
     * #validateAndConsume}, before the document's dispatch record was ever written.
     *
     * @return the {@link ConsumedAttestation} if {@code ceremonyId} is currently in state {@code
     *         CONSUMED} and its owning identity link can still be resolved; {@link Optional#empty()}
     *         for any other state (including {@code ATTEST_ANCHORED} and the terminal {@code FAILED}/
     *         {@code EXPIRED}), an unknown ceremony id, or a since-deleted identity link. Callers must
     *         treat empty as "dispatch cannot proceed" and fail closed, never falling back to a plain
     *         publish.
     */
    Optional<ConsumedAttestation> findConsumed(String ceremonyId);

}
