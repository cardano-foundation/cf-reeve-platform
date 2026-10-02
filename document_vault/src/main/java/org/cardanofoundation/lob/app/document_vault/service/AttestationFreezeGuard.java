package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;

/**
 * Checks that a ceremony's freeze still matches the document about to be published. Wired through an
 * {@code ObjectProvider} so {@code VaultDocumentService} keeps working when keri_attestation is
 * disabled: a plain publish never touches this, and an attested one fails closed with
 * {@code ATTESTATION_UNAVAILABLE}.
 *
 * <p>Runs inside {@code VaultDocumentService#publish}'s row-locked transaction, immediately before
 * {@code AttestationConsumptionApi#validateAndConsume}, so a stale or mismatched freeze cannot burn
 * the ceremony's compare-and-set to {@code CONSUMED}.
 *
 * <p>Takes the already-loaded, row-locked {@link VaultDocumentEntity} rather than a document id: the
 * caller holds it and has already run its checks, so this avoids a second lookup and guarantees the
 * freshness check reads the exact row publish is about to flip.
 */
public interface AttestationFreezeGuard {

    /**
     * Empty means "still fresh, safe to consume": the document's current envelope still matches the
     * ceremony's frozen snapshot fingerprint, and the freeze is not older than the configured
     * {@code freeze-max-age}. A present result is a fail-closed {@link ProblemDetail}:
     * {@code ATTESTATION_FREEZE_MISSING} (no freeze row for this document/ceremony pair),
     * {@code ATTESTED_CONTENT_CHANGED} (envelope drifted since ATTEST), or
     * {@code ATTESTED_METADATA_MISMATCH} (freeze older than {@code freeze-max-age}).
     */
    Optional<ProblemDetail> verifyFreshness(VaultDocumentEntity document, String ceremonyId);

}
