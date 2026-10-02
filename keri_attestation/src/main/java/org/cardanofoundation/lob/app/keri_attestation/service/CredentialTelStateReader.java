package org.cardanofoundation.lob.app.keri_attestation.service;

/**
 * Reads a credential's CURRENT issuance state from the issuer's own transaction event log, rather
 * than from whatever a presenter chose to hand over.
 *
 * <p>This is the difference between "the stream I was given contains an {@code iss} and no
 * {@code rev}" and "the issuer's registry says this credential is issued right now". A stream captured
 * before a revocation still satisfies the first and is exactly what an attacker replays; only the
 * second can see the later {@code rev}, because the presenter is not the one being asked.
 *
 * <p>An interface rather than a direct client call so the validator can be tested without a KERI agent
 * — and so the fail-closed behaviour on {@link TelStatus#UNKNOWN} is exercised as a real case rather
 * than as an error path nobody drives.
 */
public interface CredentialTelStateReader {

    enum TelStatus {
        /** The registry says this credential is currently issued ({@code iss} or {@code bis}). */
        ISSUED,
        /** The registry says it has been revoked ({@code rev} or {@code brv}). Terminal. */
        REVOKED,
        /**
         * No answer could be obtained: the registry is unknown to our agent, unreachable, or returned
         * something unrecognised. Callers must treat this as a rejection — an unanswerable question
         * about revocation is not a "no".
         */
        UNKNOWN
    }

    /**
     * @param registryId     the credential's {@code ri} — the issuer's registry.
     * @param credentialSaid the credential's own SAID.
     * @return the credential's current state, never {@code null}. Implementations must return
     *         {@link TelStatus#UNKNOWN} rather than throwing.
     */
    TelStatus statusOf(String registryId, String credentialSaid);
}
