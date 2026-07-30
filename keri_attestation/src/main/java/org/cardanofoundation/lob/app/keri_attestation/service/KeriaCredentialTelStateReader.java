package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialState;

/**
 * Asks our KERI agent for a credential's current registry state.
 *
 * <p>The agent answers from the issuer's TEL as it has actually seen it — reached through the issuer's
 * OOBI and its witnesses — so the answer does not depend on the party presenting the credential. That
 * is the whole point: a presenter can choose which events to put in a stream, but cannot choose what
 * the issuer's registry says.
 *
 * <p>The agent must already know the registry, which means the issuer's OOBI must have been resolved.
 * When it has not, the answer is {@link TelStatus#UNKNOWN} and the caller rejects — a credential whose
 * revocation state we cannot check is not one we accept.
 *
 * <p>Never cached. A revocation is only useful if it is seen promptly, and the entire reason this
 * class exists is that a stale view of revocation state is indistinguishable from an attack.
 *
 * <p>Coded defensively over the pinned signify jar, whose {@code state(...)} is declared to return a
 * raw {@code Object}: it may hand back a typed {@link CredentialState} or the decoded JSON map,
 * depending on version. Both are read here rather than assuming one — the same treatment
 * {@link KelAnchorVerifier} gives the jar's other raw responses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriaCredentialTelStateReader implements CredentialTelStateReader {

    private final KeriAttestationClient client;

    @Override
    public TelStatus statusOf(String registryId, String credentialSaid) {
        if (registryId == null || registryId.isBlank() || credentialSaid == null || credentialSaid.isBlank()) {
            return TelStatus.UNKNOWN;
        }
        try {
            Optional<Object> state = client.client().credentials().state(registryId, credentialSaid);
            if (state.isEmpty()) {
                log.warn("Registry {} has no TEL state for credential {} — treating as unverifiable.",
                        registryId, credentialSaid);
                return TelStatus.UNKNOWN;
            }
            return classify(eventTypeOf(state.get()), registryId, credentialSaid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TelStatus.UNKNOWN;
        } catch (Exception e) {
            log.warn("Could not read TEL state for credential {} in registry {}: {}", credentialSaid, registryId,
                    e.getMessage());
            return TelStatus.UNKNOWN;
        }
    }

    private static String eventTypeOf(Object state) {
        if (state instanceof CredentialState typed) {
            return typed.getEt();
        }
        if (state instanceof Map<?, ?> map) {
            Object et = map.get("et");
            return et == null ? null : String.valueOf(et);
        }
        return null;
    }

    /**
     * {@code iss}/{@code bis} are issuance, {@code rev}/{@code brv} revocation — the {@code b} pair
     * being the registrar-backed variants of the same two events. Anything else is a state this code
     * does not understand, which is not the same as "fine".
     */
    private static TelStatus classify(String eventType, String registryId, String credentialSaid) {
        if (eventType == null) {
            log.warn("TEL state for credential {} in registry {} carries no event type — treating as "
                    + "unverifiable.", credentialSaid, registryId);
            return TelStatus.UNKNOWN;
        }
        return switch (eventType) {
            case "iss", "bis" -> TelStatus.ISSUED;
            case "rev", "brv" -> TelStatus.REVOKED;
            default -> {
                log.warn("Unrecognised TEL event type '{}' for credential {} in registry {} — treating as "
                        + "unverifiable.", eventType, credentialSaid, registryId);
                yield TelStatus.UNKNOWN;
            }
        };
    }
}
