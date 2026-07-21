package org.cardanofoundation.lob.app.document_vault.service;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import org.cardanofoundation.lob.app.document_vault.domain.entity.VaultDocumentEntity;

/**
 * Port implemented by {@code blockchain_publisher} (design §5.1 step 2 / §5.2, Task 14): the
 * freeze it owns (Task 13's {@code document_attestation_freeze}) is the only place that knows what
 * envelope bytes a ceremony actually attested and how old that freeze is, so document_vault cannot
 * run this check itself — it depends on this port instead, wired via {@code ObjectProvider} exactly
 * like {@link org.cardanofoundation.lob.app.blockchain_common.service.IpfsAvailability} above it, so
 * {@code VaultDocumentService} keeps working with {@code keri_attestation}/{@code
 * blockchain_publisher} disabled (a bodiless publish never touches this at all; an attested publish
 * with no implementation available fails closed with {@code ATTESTATION_UNAVAILABLE}).
 *
 * <p>Runs inside {@code VaultDocumentService#publish}'s row-locked transaction, immediately BEFORE
 * {@code AttestationConsumptionApi#validateAndConsume} — a stale or content-mismatched freeze must
 * never be allowed to reach (and burn) the ceremony's compare-and-set to {@code CONSUMED}.
 *
 * <p>Takes the already-loaded, row-locked {@link VaultDocumentEntity} rather than a bare document id:
 * the caller already holds it (and has already run the org-membership/DRAFT checks against it) inside
 * its own transaction, so handing it over avoids a second, redundant lookup and keeps the freshness
 * check reading the exact same row {@code publish} is about to flip to {@code PUBLISHED}.
 */
public interface AttestationFreezeGuard {

    /**
     * Empty means "still fresh, safe to consume": the document's current envelope still matches the
     * ceremony's frozen snapshot fingerprint, and the freeze is not older than the configured
     * {@code freeze-max-age}. A present result is a fail-closed {@link ProblemDetail} (design §5.2):
     * {@code ATTESTATION_FREEZE_MISSING} (no freeze row for this document/ceremony pair),
     * {@code ATTESTED_CONTENT_CHANGED} (envelope drifted since ATTEST), or
     * {@code ATTESTED_METADATA_MISMATCH} (freeze older than {@code freeze-max-age}).
     */
    Optional<ProblemDetail> verifyFreshness(VaultDocumentEntity document, String ceremonyId);

}
