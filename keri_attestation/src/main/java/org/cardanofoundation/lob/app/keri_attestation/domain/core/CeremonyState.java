package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * A ceremony's progress through the KERI wallet-attestation flow. Terminal states are
 * {@link #CONSUMED} (the happy path: bound into a publish transaction), {@link #FAILED} (retryable,
 * carries {@code errorTitle}/{@code errorDetail}) and {@link #EXPIRED} (TTL sweep or lazy check).
 *
 * <pre>
 * CREATED -&gt; OOBI_RESOLVED -&gt; CREDENTIAL_REQUESTED -&gt; CREDENTIAL_RECEIVED
 *   -&gt; AUTH_BEGIN_SUBMITTED -&gt; AUTH_BEGIN_CONFIRMED
 *   -&gt; ATTEST_REQUESTED -&gt; ATTEST_ANCHORED -&gt; CONSUMED
 * any non-terminal state -&gt; FAILED | EXPIRED
 * </pre>
 */
public enum CeremonyState {
    CREATED,
    OOBI_RESOLVED,
    CREDENTIAL_REQUESTED,
    CREDENTIAL_RECEIVED,
    AUTH_BEGIN_SUBMITTED,
    AUTH_BEGIN_CONFIRMED,
    ATTEST_REQUESTED,
    ATTEST_ANCHORED,
    CONSUMED,
    FAILED,
    EXPIRED
}
