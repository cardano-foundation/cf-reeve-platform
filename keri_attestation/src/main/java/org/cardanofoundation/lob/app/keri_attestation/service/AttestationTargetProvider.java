package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Optional;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationDigest;

/**
 * Port implemented once per attestable target type. {@code DOCUMENT} is implemented by
 * {@code document_vault}; another module attests its own aggregates by adding an implementation
 * without touching {@code keri_attestation}. {@link AttestationTargetProviderRegistry} collects every
 * bean implementing this interface, keyed by {@link #targetType()}.
 *
 * <p>See {@code keri_attestation/README.md} for the full checklist of what a new target type needs.
 */
public interface AttestationTargetProvider {

    /** e.g. {@code "DOCUMENT"}. Must be unique across all providers and stable once ceremonies exist. */
    String targetType();

    /** Authorization check (publish rights, target in attestable state). Called at ceremony
     *  creation AND again at the attest step. */
    Optional<ProblemDetail> authorize(String targetId, String userId);

    /**
     * The organisation the target belongs to, recorded on the ceremony at creation time.
     *
     * <p>Needed because AUTH_BEGIN is published through {@code blockchain_publisher}'s dispatcher,
     * which is organisation-scoped: a row whose organisation does not resolve is never picked up.
     * Returning empty fails ceremony creation rather than producing a ceremony that can never publish.
     *
     * <p>Called immediately after {@link #authorize}, so an implementation may assume the caller is
     * allowed to see the target.
     */
    Optional<String> organisationId(String targetId);

    /** Freeze the target's metadata for this ceremony and return the digest to attest.
     *  Idempotent per (targetId, ceremonyId). */
    Either<ProblemDetail, AttestationDigest> prepareDigest(String targetId, String ceremonyId);
}
