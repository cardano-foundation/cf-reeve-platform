package org.cardanofoundation.lob.app.keri_attestation.service;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationClient;

/**
 * Fetches a credential's full CESR chain — the raw stream KERIA served, not a reassembled one.
 *
 * <p>Goes at the endpoint directly rather than through {@code credentials().get(said)}: that returns
 * the STRUCTURED credential, which has already lost the surrounding {@code vcp}/{@code iss} events and
 * the attached signatures. Those events are exactly what {@link CredentialChainValidator} verifies and
 * what {@link CesrChainReducer} reduces for on-chain publication, so anything short of the byte stream
 * KERIA produced is unusable here.
 *
 * <p>Two callers need it — presentation ({@link KeriCredentialService}) and publication
 * ({@link KeriAuthBeginService}) — and they must agree on the endpoint, the Accept header and what a
 * non-200 means, hence one copy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class CredentialCesrFetcher {

    private final KeriAttestationClient client;

    /**
     * @return the full CESR chain for {@code credentialSaid}, or empty when the agent answered that it
     *         does not hold it (404).
     * @throws IllegalStateException on any other non-200 — a 5xx or a gateway error is the agent being
     *         broken, not the agent saying no, and the callers report those two very differently. This
     *         used to collapse every non-200 into empty, which turned a KERIA outage into the permanent
     *         "credential was not found in the store" that fails the step for good.
     */
    public Optional<String> fetch(String credentialSaid) throws Exception {
        HttpResponse<String> response = client.client().fetch(
                "/credentials/" + credentialSaid, "GET", null, Map.of("Accept", "application/json+cesr"));
        int status = response.statusCode();
        if (status == 200) {
            return Optional.ofNullable(response.body());
        }
        if (status == 404) {
            log.warn("credential {} is not held by the agent (404)", credentialSaid);
            return Optional.empty();
        }
        throw new IllegalStateException(
                "The KERI agent answered %d fetching the CESR chain for credential %s.".formatted(status,
                        credentialSaid));
    }

}
