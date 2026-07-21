package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;

/**
 * Port implemented once per attestable target type (design §3.3): {@code DOCUMENT} is implemented in
 * {@code blockchain_publisher} (design §5.2); a future report-path provider adds its own
 * implementation without touching {@code keri_attestation}. {@link AttestationTargetProviderRegistry}
 * collects every bean implementing this interface, keyed by {@link #targetType()}. Copied verbatim
 * from spec §3.3 — this exact shape is the reuse seam later tasks implement against.
 */
public interface AttestationTargetProvider {

    /** e.g. {@code "DOCUMENT"}. */
    String targetType();

    /** Authorization check (publish rights, target in attestable state). Called at ceremony
     *  creation AND again at the attest step. */
    Optional<ProblemDetail> authorize(String targetId, String userId);

    /** Freeze the target's metadata for this ceremony and return the digest to attest.
     *  Idempotent per (targetId, ceremonyId). */
    Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId);
}
