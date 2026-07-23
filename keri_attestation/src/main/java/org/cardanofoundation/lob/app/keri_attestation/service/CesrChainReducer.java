package org.cardanofoundation.lob.app.keri_attestation.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import org.cardanofoundation.signify.cesr.util.CESRStreamUtil;

/**
 * Reduces a full KERI/ACDC CESR credential stream — as returned by
 * {@code client.credentials().get(said)} — down to just the events needed to reconstruct and verify
 * the chain from its bytes alone: registry inception ({@code vcp}), issuance ({@code iss}) and the
 * bare ACDC payloads, concatenated in that canonical (vcp, then iss, then ACDC) order.
 *
 * <p>The reduced bytes are what eventually gets chunked onto Cardano under CIP-170 label 170's
 * {@code c} field (design §4.5), so a verifier reconstructing the chain from on-chain bytes must see
 * exactly what this reduction (and KERIA) agree constitutes "the chain": {@code icp}/{@code ixn}/
 * {@code rot} KEL events and {@code rev} TEL events are intentionally dropped — deep KEL signature
 * verification stays with KERIA (see {@link CredentialChainValidator}'s javadoc for the same
 * boundary).
 *
 * <p>Pure and stateless: a deterministic function of its input, no I/O, no KERI agent.
 */
@Service
public class CesrChainReducer {

    /**
     * @param fullCesr the full CESR stream for one credential, as returned by
     *                 {@code client.credentials().get(said)}
     * @return the reduced stream's UTF-8 bytes: every {@code vcp} event (with attachment), then every
     *         {@code iss} event (with attachment), then every bare ACDC event (attachment always
     *         {@code ""} — an ACDC's own signature lives in its {@code iss} event's attachment, not on
     *         the ACDC itself), each group in stream order. Re-parses without loss via
     *         {@link CESRStreamUtil#parseCESRData(String)}.
     */
    @SuppressWarnings("unchecked")
    public byte[] reduceToVcpIssAcdc(String fullCesr) {
        List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(fullCesr);

        List<Map<String, Object>> vcpEvents = new ArrayList<>();
        List<String> vcpAttachments = new ArrayList<>();
        List<Map<String, Object>> issEvents = new ArrayList<>();
        List<String> issAttachments = new ArrayList<>();
        List<Map<String, Object>> acdcEvents = new ArrayList<>();
        List<String> acdcAttachments = new ArrayList<>();

        for (Map<String, Object> eventData : cesrData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");

            Object eventTypeObj = event.get("t");
            if (eventTypeObj != null) {
                switch (eventTypeObj.toString()) {
                    case "vcp" -> {
                        vcpEvents.add(event);
                        vcpAttachments.add((String) eventData.get("atc"));
                    }
                    case "iss" -> {
                        issEvents.add(event);
                        issAttachments.add((String) eventData.get("atc"));
                    }
                    default -> {
                        // icp/ixn/rot/rev/... are not part of the reduced on-chain chain.
                    }
                }
            } else if (isAcdc(event)) {
                acdcEvents.add(event);
                acdcAttachments.add("");
            }
        }

        List<Map<String, Object>> combinedEvents = new ArrayList<>();
        List<String> combinedAttachments = new ArrayList<>();
        combinedEvents.addAll(vcpEvents);
        combinedEvents.addAll(issEvents);
        combinedEvents.addAll(acdcEvents);
        combinedAttachments.addAll(vcpAttachments);
        combinedAttachments.addAll(issAttachments);
        combinedAttachments.addAll(acdcAttachments);

        String stripped = CESRStreamUtil.makeCESRStream(combinedEvents, combinedAttachments);
        return stripped.getBytes(StandardCharsets.UTF_8);
    }

    /** ACDCs carry no {@code "t"} (event-type) field — they're identified by having {@code s}
     *  (schema), {@code a} (attributes) and {@code i} (issuer) instead. */
    private static boolean isAcdc(Map<String, Object> event) {
        return event.containsKey("s") && event.containsKey("a") && event.containsKey("i") && event.get("s") != null;
    }
}
