package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Builds the remotesign request KED sent to a linked wallet AID to anchor a metadata digest (design
 * §4.4/§4.6 step 3).
 *
 * <p><b>PROVISIONAL — pending the {@code RemotesignAnchorSpike} verdict.</b> The Task 8 Veridian
 * spike ({@code docs/keri/spike/RemotesignAnchorSpike.java}, see {@code keri_attestation/README.md}
 * "Spike findings") has <em>not</em> been run against a real wallet yet; that is a manual,
 * human-operated gate. This class returns KED variant A from the spike — an insertion-ordered map
 * {@code {"d": <digestQb64>}} — mirroring the proven seal shape used by this repo's own
 * {@code docs/keri/AttestTransaction.java} (`client.identifiers().interact(name, Map.of("d",
 * qb64))`), which anchors a caller-chosen digest directly as the interaction event's seal when the
 * backend signs for itself. Whether Veridian's remotesign flow accepts this exact shape from a
 * <em>different</em> requesting party is exactly what the spike answers.
 *
 * <p>If the spike shows Veridian anchors a different shape (KED variant B or C, or something else
 * entirely), <strong>only this class changes</strong> — {@link KeriAttestService} consumes it as a
 * single opaque KED-building step and never constructs the request map itself, by design, so a wrong
 * guess here is a one-class fix, not a scattered one.
 */
@Service
public class RemotesignRequestFactory {

    /**
     * @param walletAid    the linked wallet's AID (unused by variant A; kept in the signature so a
     *                     future variant that needs it — e.g. spike variant B, {@code {"i":
     *                     walletAid, "d": digestQb64}} — is a body-only change)
     * @param digestQb64   the CESR digest to anchor (equals the ceremony's {@code metadataDigest})
     * @return the KED to send as the exn payload ({@code a}) of a {@code /remotesign/ixn/req}
     *         exchange
     */
    public Map<String, Object> anchorRequestKed(String walletAid, String digestQb64) {
        Map<String, Object> ked = new LinkedHashMap<>();
        ked.put("d", digestQb64);
        return ked;
    }
}
