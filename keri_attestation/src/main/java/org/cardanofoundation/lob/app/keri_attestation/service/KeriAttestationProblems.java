package org.cardanofoundation.lob.app.keri_attestation.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Problem-detail titles and factories for the {@code keri_attestation} module, styled after
 * document_vault's {@code VaultProblems}: a flat set of title constants plus a handful of generic
 * status-shaped factories that callers compose with a title and a human-readable detail. Titles
 * beyond the ones {@link CeremonyService} itself raises (e.g. {@link #KERI_WALLET_TIMEOUT}) are
 * reserved here for the KERI-wallet-interaction services added in later tasks, so every consumer
 * shares one canonical vocabulary of problem titles.
 */
public final class KeriAttestationProblems {

    public static final String CEREMONY_NOT_FOUND = "CEREMONY_NOT_FOUND";
    public static final String CEREMONY_FORBIDDEN = "CEREMONY_FORBIDDEN";
    public static final String CEREMONY_INVALID_STATE = "CEREMONY_INVALID_STATE";
    public static final String CEREMONY_EXPIRED = "CEREMONY_EXPIRED";
    public static final String CEREMONY_LIMIT_REACHED = "CEREMONY_LIMIT_REACHED";
    public static final String STEP_COOLDOWN = "STEP_COOLDOWN";
    public static final String IDENTITY_NOT_LINKED = "IDENTITY_NOT_LINKED";
    public static final String IDENTITY_RELINKED = "IDENTITY_RELINKED";
    public static final String KERI_WALLET_TIMEOUT = "KERI_WALLET_TIMEOUT";
    public static final String KERI_STEP_TIMED_OUT = "KERI_STEP_TIMED_OUT";
    public static final String CREDENTIAL_REJECTED = "CREDENTIAL_REJECTED";
    /** An IPEX exchange (apply/agree/admit) could not be built or sent to the linked AID — a
     *  transport/protocol failure talking to the KERI agent, distinct from {@link #KERI_WALLET_TIMEOUT}
     *  (no reply arrived in time) and {@link #CREDENTIAL_REJECTED} (a reply arrived but failed content
     *  validation). Added for {@code KeriCredentialService} (design §4.3). */
    public static final String CREDENTIAL_REQUEST_FAILED = "CREDENTIAL_REQUEST_FAILED";
    public static final String AUTH_BEGIN_ROLLED_BACK = "AUTH_BEGIN_ROLLED_BACK";
    public static final String AUTH_BEGIN_UNVERIFIED = "AUTH_BEGIN_UNVERIFIED";
    /** F9 fix: {@code KeriAuthBeginService#submitOwn} could not build/submit a fresh AUTH_BEGIN
     *  transaction because no {@code CardanoMetadataTxSubmitter} implementation is available in this
     *  deployment (the module is enabled without {@code blockchain_publisher}). Distinct from
     *  {@link #AUTH_BEGIN_UNVERIFIED}, which is the external-authority-verification path's equivalent
     *  failure for the same underlying cause. */
    public static final String AUTH_BEGIN_SUBMISSION_UNAVAILABLE = "AUTH_BEGIN_SUBMISSION_UNAVAILABLE";
    public static final String ATTESTATION_UNAVAILABLE = "ATTESTATION_UNAVAILABLE";
    public static final String OOBI_INVALID = "OOBI_INVALID";
    public static final String TARGET_MISMATCH = "TARGET_MISMATCH";
    /** A wallet-confirmed remotesign anchor could not be verified against the ceremony's
     *  {@code metadataDigest} — no matching interaction event was found on the AID's KEL, or the
     *  event's seal list did not contain the expected digest (design §4.6 step 5). Also used for an
     *  unexpected error while performing that verification, since an unsupervised async worker must
     *  always resolve the ceremony rather than propagate (mirrors {@code KeriCredentialService}'s
     *  reuse of {@link #CREDENTIAL_REJECTED} for its validator's thrown-exception case). */
    public static final String ATTEST_SEAL_MISMATCH = "ATTEST_SEAL_MISMATCH";
    /** The ATTEST remotesign request itself could not be built or sent (no
     *  {@code AttestationTargetProvider} registered for the ceremony's target type, no local agent
     *  HabState, transport/protocol failure talking to the KERI agent) — distinct from
     *  {@link #KERI_WALLET_TIMEOUT} (no reply arrived in time) and {@link #ATTEST_SEAL_MISMATCH} (a
     *  reply arrived but did not verify). Mirrors {@link #CREDENTIAL_REQUEST_FAILED}'s role for IPEX. */
    public static final String ATTEST_REQUEST_FAILED = "ATTEST_REQUEST_FAILED";

    private KeriAttestationProblems() {
    }

    private static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail notFound(String title, String detail) {
        return of(HttpStatus.NOT_FOUND, title, detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return of(HttpStatus.FORBIDDEN, CEREMONY_FORBIDDEN, detail);
    }

    public static ProblemDetail conflict(String title, String detail) {
        return of(HttpStatus.CONFLICT, title, detail);
    }

    public static ProblemDetail unprocessable(String title, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, title, detail);
    }
}
