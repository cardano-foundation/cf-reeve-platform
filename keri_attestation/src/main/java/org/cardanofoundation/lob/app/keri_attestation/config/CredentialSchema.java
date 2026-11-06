package org.cardanofoundation.lob.app.keri_attestation.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * One credential schema this deployment is willing to accept, and the rule for deciding whether a
 * credential of that schema was issued by somebody we trust.
 *
 * <p>Adding a schema is a configuration change: nothing about a new schema requires code, because the
 * only thing that varies between schemas is which of the two trust models applies and which AIDs
 * populate it. Deployments differ in exactly that, so it belongs in configuration rather than in a
 * class per credential type.
 *
 * @param said          SAID of the schema itself. The identity of the schema, and what a presented
 *                      credential is gated on.
 * @param name          human-readable label, shown in the UI when a credential of this schema is
 *                      displayed. Falls back to the SAID when absent.
 * @param trustModel    how trust is established for this schema — see {@link TrustModel}.
 * @param trustedRoots  for {@link TrustModel#CHAINED}: the AIDs a credential's issuance chain must
 *                      terminate at. Ignored for {@code STANDALONE}.
 * @param trustedIssuers for {@link TrustModel#STANDALONE}: the AIDs allowed to issue this schema
 *                      directly. Ignored for {@code CHAINED}.
 * @param oobis         OOBIs to resolve so the agent can verify credentials of this schema. Resolved
 *                      lazily at first use rather than at startup, so a boot does not depend on every
 *                      issuer being reachable.
 */
public record CredentialSchema(
        String said,
        String name,
        TrustModel trustModel,
        List<String> trustedRoots,
        List<String> trustedIssuers,
        List<String> oobis) {

    /**
     * How a credential of a schema earns trust.
     *
     * <p>An enum rather than a boolean: the two models consult different AID lists, and a boolean makes
     * "which list did this deployment actually mean?" ambiguous at exactly the point where getting it
     * wrong means trusting the wrong issuer.
     */
    public enum TrustModel {
        /**
         * The credential's issuance chain is followed to its root, which must be one of
         * {@link #trustedRoots()}. This is the vLEI shape: a Legal Entity credential is trusted because
         * the chain above it terminates at GLEIF, not because its immediate issuer (a QVI) is known.
         */
        CHAINED,
        /**
         * The credential's own issuer must be one of {@link #trustedIssuers()}. There is no chain to
         * follow — the issuer is the authority. This is the shape of a credential an organisation
         * issues about its own people.
         */
        STANDALONE
    }

    /** @return the AID list this schema's trust model actually consults. */
    public List<String> trustAnchors() {
        List<String> anchors = trustModel == TrustModel.CHAINED ? trustedRoots : trustedIssuers;
        return anchors == null ? List.of() : anchors;
    }

    /** @return {@link #name()}, or the SAID when no name was configured. */
    public String displayName() {
        return name == null || name.isBlank() ? said : name;
    }

    /**
     * A short digest of the trust policy this entry expresses, stored alongside every verdict it
     * produces.
     *
     * <p>Trust decisions are only meaningful relative to the configuration in force when they were
     * made. Recording which one that was is what separates "this was verified under a policy we have
     * since changed" from "this is verified", instead of letting an edit to the trusted-root list
     * silently restate every past verdict as if it had been made under the new rules.
     *
     * <p>Covers the model and the anchors, not the display name: renaming a schema does not change
     * what was trusted.
     */
    public String policyFingerprint() {
        String canonical = said + "|" + trustModel + "|" + String.join(",", trustAnchors());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM this runs on.", e);
        }
    }
}
