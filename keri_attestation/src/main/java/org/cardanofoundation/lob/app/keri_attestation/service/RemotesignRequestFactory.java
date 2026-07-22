package org.cardanofoundation.lob.app.keri_attestation.service;

import java.security.DigestException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.cardanofoundation.signify.cesr.Saider;

/**
 * Builds the remotesign request KED sent to a linked wallet AID to anchor a metadata digest (design
 * §4.4/§4.6 step 3).
 *
 * <p><b>Aligned with the cip113 wallet contract (design §4.4 rev 3, user-directed 2026-07-22 after
 * live wallet testing).</b> The direct-digest KED this class used to build (spike "variant A", a bare
 * insertion-ordered {@code {"d": <digestQb64>}} map) was never accepted by a real Veridian wallet —
 * sending it produced no notification in the wallet at all. {@code cip113-programmable-tokens-platform}'s
 * {@code KeriService#requestAttestation} is the proven, wallet-verified reference: it builds an
 * insertion-ordered payload with {@code i} present <em>before</em> saidifying, then sends the
 * <em>saidified</em> payload (not the raw digest) as the exn's {@code a}. Veridian's
 * {@code processRemoteSignReq} apparently expects — and silently drops, with no UI surfaced, anything
 * that doesn't look like — a self-addressing payload: {@code i} present, {@code d} the SAID of the
 * whole payload (not a caller-chosen opaque digest), computed with {@code i} already in place so the
 * wallet's own SAID recomputation over the received payload (which always sees {@code i}) matches ours.
 *
 * <p>This class now returns that shape: an insertion-ordered map {@code {i: <walletAid>, d: "",
 * metadataLabel: <label>, metadataDigest: <digestQb64>}} run through {@link Saider#saidify(Map)},
 * which overwrites {@code d} with the SAID of the whole map. The returned map's own {@code d} is what
 * the wallet is expected to anchor as its interaction-event seal — see
 * {@link KeriAttestService}'s seal-verification javadoc (it now compares against this SAID, not the
 * raw {@code metadataDigest}, which is kept on the ceremony separately for 1447/freeze-digest
 * matching only — design §4.4 rev 3).
 *
 * <p>{@link KeriAttestService} still consumes this as a single opaque KED-building step and never
 * constructs the request map itself, by design, so any further wallet-observed shape correction stays
 * a one-class fix.
 */
@Service
public class RemotesignRequestFactory {

    /**
     * @param walletAid         the linked wallet's AID — becomes the payload's {@code i} (cip113:
     *                          "CRITICAL... Inserting i first ourselves keeps the SAIDs in sync")
     * @param metadataLabel     the Cardano metadata label the attested content is published under
     *                          (e.g. {@code "1447"}) — carried in the payload so the anchored SAID
     *                          also commits to which label the digest belongs to
     * @param metadataDigestQb64 the ceremony's {@code metadataDigest} (the CESR digest of the raw
     *                          label-{@code metadataLabel} metadata value) to anchor
     * @return the saidified KED to send as the exn payload ({@code a}) of a
     *         {@code /remotesign/ixn/req} exchange; its own {@code d} field is the payload SAID the
     *         wallet is expected to anchor as the KEL interaction event's seal
     */
    public Map<String, Object> anchorRequestKed(String walletAid, String metadataLabel, String metadataDigestQb64) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("i", walletAid);
        payload.put("d", "");
        payload.put("metadataLabel", metadataLabel);
        payload.put("metadataDigest", metadataDigestQb64);
        try {
            return Saider.saidify(payload).sad();
        } catch (DigestException e) {
            throw new IllegalStateException("Failed to compute the SAID of the remotesign request payload.", e);
        }
    }
}
