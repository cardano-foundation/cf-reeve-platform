package org.cardanofoundation.lob.app.keri_attestation.domain.view;

import java.time.Instant;

/**
 * The current platform user's KERI identity-link status, assembled from their
 * {@code KeriIdentityLinkEntity} row, if any. {@code linked=false} (every other field {@code null}) is
 * the ordinary answer for a user who has never resolved an OOBI — this is a {@code 200}, not a
 * {@code 404}: the frontend polls this endpoint to decide whether to show the linking flow at all.
 */
public record IdentityView(boolean linked, String aid, IdentityCredentialView credential, AuthBeginView authBegin) {

    /**
     * The validated leaf credential's SAID and schema SAID, set together once a
     * successful IPEX presentation completes. {@code null} while the link has no credential yet.
     */
    public record IdentityCredentialView(String said, String schemaSaid) {
    }

    /**
     * {@code txHash}/{@code at} come straight off {@code KeriIdentityLinkEntity}. {@code external} is
     * always {@code false}: the entity persists the confirmed (or verified-external) AUTH_BEGIN tx hash
     * identically regardless of which path wrote it — {@code KeriAuthBeginService
     * #persistAuthBeginIfIdentityStillCurrent} is the sole writer for both the {@code verifyExternal}
     * skip and the async on-chain-confirmation path, and carries no "how did this get here" flag to
     * read back. Reporting anything other than a constant {@code false} here would be a guess, not a
     * fact read off the entity — revisit if/when the entity gains one.
     */
    public record AuthBeginView(String txHash, Instant at, boolean external) {
    }
}
