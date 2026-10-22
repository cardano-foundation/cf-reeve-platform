package org.cardanofoundation.lob.app.keri_attestation.domain.view;

import java.time.Instant;
import java.util.Map;

import jakarta.annotation.Nullable;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;
import org.cardanofoundation.lob.app.keri_attestation.domain.core.AttestationStatus;

/**
 * What a consumer is told about a card's credential attestation.
 *
 * <p>Deliberately does NOT carry the credential CESR chain. That is bulk verification input with no
 * display value, and shipping it to a browser would put a third party's full credential chain in
 * front of anyone who can see the addressbook.
 *
 * @param leafIssuerAid  who issued the credential.
 * @param trustAnchorAid what trust was established against. Different from the issuer in a chained
 *                       credential, and showing one as the other misstates both facts.
 * @param verifiedAt     when the checks ran. The claim is "verified at import", not "valid now" — a
 *                       credential revoked afterwards is not detected until a revalidation sweep
 *                       exists, and any UI must say so rather than imply currency.
 */
public record CredentialAttestationView(
        AttestationStatus status,
        Instant verifiedAt,
        @Nullable String attesterAid,
        @Nullable String schemaSaid,
        @Nullable String schemaName,
        @Nullable String leafIssuerAid,
        @Nullable String trustAnchorAid,
        @Nullable TrustModel trustModel,
        @Nullable String kelSequence,
        @Nullable String kelEventSaid,
        Map<String, Object> claims) {
}
