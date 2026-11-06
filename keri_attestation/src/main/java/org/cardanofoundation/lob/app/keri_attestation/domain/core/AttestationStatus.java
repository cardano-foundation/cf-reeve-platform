package org.cardanofoundation.lob.app.keri_attestation.domain.core;

/**
 * What is actually known about a stored card's credential attestation.
 *
 * <p>An enum rather than a boolean, because "not verified" covers three genuinely different
 * situations and collapsing them into {@code false} would assert something untrue about two of them.
 * A row that was never checked did not fail a check.
 */
public enum AttestationStatus {

    /** Checked, and every check passed, under the policy recorded alongside it. */
    VERIFIED,

    /**
     * Imported before trust checking existed. Structurally validated at the time, never trust-checked,
     * and not retro-verifiable offline. Renders as "unknown", never as failed — and never as verified.
     */
    UNKNOWN_LEGACY,

    /**
     * Verified at import, but the credential has since been revoked by its issuer. Nothing writes this
     * yet: continuous revalidation is a separate piece of work, and the state exists so a later sweep
     * has somewhere to record its finding rather than having to overload {@link #VERIFIED}.
     */
    REVOKED_AFTER_IMPORT
}
