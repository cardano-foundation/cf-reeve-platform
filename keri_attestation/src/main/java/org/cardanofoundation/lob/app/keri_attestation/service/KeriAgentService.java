package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.LinkedHashMap;
import java.util.List;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.keri_attestation.config.SignifyClientConfig.IdentifierRecord;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * Exposes the platform's own KERI agent identity to the rest of the module (design §4.3). The
 * agent AID itself is looked up/created eagerly by
 * {@link org.cardanofoundation.lob.app.keri_attestation.config.SignifyClientConfig}'s
 * {@code keriAttestationAgentIdentifier} bean — by the time this service exists, Spring has already
 * forced that bean (and therefore the AID) into being, since it's a required constructor argument
 * here. This class's own {@link PostConstruct} bootstrap resolves and caches the agent's OOBI URL
 * the same "fail at startup, not on first request" way, so a KERI agent that's unreachable for OOBI
 * publication is caught during application startup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class KeriAgentService {

    private static final String AGENT_ROLE = "agent";

    @Qualifier("keriAttestationSignifyClient")
    private final SignifyClient client;
    private final IdentifierRecord identifier;

    private volatile String cachedAgentOobi;

    @PostConstruct
    void init() throws Exception {
        cachedAgentOobi = fetchAgentOobi();
        log.info("KERI agent {} ({}) OOBI: {}", identifier.name(), identifier.prefix(), cachedAgentOobi);
    }

    public String agentPrefix() {
        return identifier.prefix();
    }

    public String agentName() {
        return identifier.name();
    }

    public String agentOobi() {
        return cachedAgentOobi;
    }

    /**
     * {@code client.oobis().get(name, "agent")} returns a {@code LinkedHashMap} whose {@code "oobis"}
     * entry is a list of OOBI URLs for the agent's end role. The legacy idiom (blockchain_publisher's
     * {@code KeriConfig.createAid}) stringifies the whole list and strips the surrounding brackets with
     * a regex, which silently concatenates every entry into one string when there's more than one
     * witness-visible OOBI. Here we index the first entry directly instead — same bracket-free result
     * for the common single-entry case, and well-defined (rather than mangled) when there are several.
     */
    private String fetchAgentOobi() throws Exception {
        Object oobi = client.oobis().get(identifier.name(), AGENT_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "No OOBI available for KERI agent identifier " + identifier.name()));
        Object oobisValue = ((LinkedHashMap<?, ?>) oobi).get("oobis");
        if (!(oobisValue instanceof List<?> oobisList) || oobisList.isEmpty()) {
            throw new IllegalStateException(
                    "KERI agent OOBI response contained no oobis for identifier " + identifier.name());
        }
        return oobisList.get(0).toString();
    }
}
