package org.cardanofoundation.lob.app.keri_attestation.service;

import java.security.DigestException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.cardanofoundation.signify.cesr.Saider;

/**
 * Builds the remotesign request KED sent to a linked wallet AID to anchor a metadata digest.
 *
 * <p>The payload must be self-addressing or a Veridian wallet silently drops it, surfacing no
 * notification at all: {@code i} must be present before saidifying, and {@code d} must be the SAID of
 * the whole payload rather than a caller-chosen digest, so the wallet's own SAID recomputation over
 * what it received matches ours.
 *
 * <p>This returns an insertion-ordered map {@code {i: <walletAid>, d: "", metadataLabel: <label>,
 * metadataDigest: <digestQb64>}} run through {@link Saider#saidify(Map)}, which overwrites {@code d}
 * with the SAID of the whole map. That SAID is what the wallet anchors as its interaction-event seal,
 * and what {@link KeriAttestService} verifies against — not the raw {@code metadataDigest}, which the
 * ceremony keeps separately for freeze-digest matching.
 *
 * <p>{@link KeriAttestService} still consumes this as a single opaque KED-building step and never
 * constructs the request map itself, by design, so any further wallet-observed shape correction stays
 * a one-class fix.
 */
@Service
public class RemotesignRequestFactory {

    /**
     * @param walletAid         the linked wallet's AID — becomes the payload's {@code i}. Must be
     *                          inserted before saidifying: signify's exchange-message builder does
     *                          {@code attrs.put("i", recipient); attrs.putAll(payload)} before the
     *                          wire send, so a payload SAID computed without {@code i} would mismatch
     *                          the SAID the wallet recomputes over the received (with-{@code i})
     *                          payload
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
