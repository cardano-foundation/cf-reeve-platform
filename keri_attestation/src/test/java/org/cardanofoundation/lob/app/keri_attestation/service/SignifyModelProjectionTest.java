package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.signify.app.coring.Coring;
import org.cardanofoundation.signify.app.coring.KeyStates;
import org.cardanofoundation.signify.generated.keria.model.AgentConfig;
import org.cardanofoundation.signify.generated.keria.model.Exn;
import org.cardanofoundation.signify.generated.keria.model.KeyEvent;

/**
 * Pins the boundary where signify's typed models are projected back into the generic maps this module
 * reads events through, and pins the return TYPES this module's structural assumptions rest on.
 *
 * <p>Written after the move to signify main, where several of these went wrong at once. Each was a
 * place that walked a raw {@code Object} and tested {@code instanceof Map}, or cast one to {@code Map}
 * outright. Against a typed return the {@code instanceof} never matches, so the code compiled cleanly
 * and then did nothing — an empty KEL that reads as "the wallet never signed", an exchange that reads
 * as "no notification arrived". Silence, not failure, which is the expensive kind. (The cast form is
 * the kinder failure: it at least throws, though only at runtime, and in
 * {@code getAvailableWitnesses}' case only on the first boot against a fresh KERIA account.)
 *
 * <p>Two things are guarded here. For the projections, what matters is the KEY NAMES: the result must
 * carry the wire form ({@code t}, {@code d}, {@code a}) and not Java field names, because every
 * consumer indexes by the wire name. For the return types, what matters is that they stay the concrete
 * models the callers now dereference — if a future bump loosens one back to {@code Object}, or swaps
 * the model, this fails at build time rather than at a customer's startup.
 */
class SignifyModelProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aKeyEventProjectsToItsWireFieldNames() {
        // KelAnchorVerifier#fetchIxnEvents does exactly this conversion, and every seal check
        // downstream indexes the result by wire name.
        KeyEvent event = new KeyEvent();
        event.setT("ixn");
        event.setD("EEventSaid");
        event.setI("EIssuerAid");
        event.setS("3");
        event.setA(List.of(Map.of("i", "ERegistryId", "s", "0", "d", "EAnchoredPayloadSaid")));

        Map<String, Object> projected = MAPPER.convertValue(event, new TypeReference<>() {
        });

        assertEquals("ixn", projected.get("t"));
        assertEquals("EEventSaid", projected.get("d"));
        assertEquals("EIssuerAid", projected.get("i"));
        assertEquals("3", projected.get("s"));
        assertTrue(projected.get("a") instanceof List<?>, "seals must survive as a list");

        // The seal list is what KelAnchorVerifier#sealContainsDigest actually reads.
        List<?> seals = (List<?>) projected.get("a");
        assertTrue(seals.get(0) instanceof Map<?, ?>);
        assertEquals("EAnchoredPayloadSaid", ((Map<?, ?>) seals.get(0)).get("d"));
    }

    @Test
    void anExnProjectsToItsWireFieldNames() {
        // KeriNotificationCorrelator#extractExn does this conversion; callers then read exn.get("i"),
        // exn.get("r"), exn.get("a") and exn.get("e") by wire name.
        Exn exn = new Exn();
        exn.setI("ESenderAid");
        exn.setR("/remotesign/ixn/ref");
        exn.setD("EExnSaid");
        exn.setP("EPriorSaid");
        exn.setA(Map.of("said", "EAnchoredPayloadSaid"));
        exn.setE(Map.of("acdc", Map.of("d", "ECredentialSaid")));

        Map<String, Object> projected = MAPPER.convertValue(exn, new TypeReference<>() {
        });

        assertEquals("ESenderAid", projected.get("i"));
        assertEquals("/remotesign/ixn/ref", projected.get("r"));
        assertEquals("EExnSaid", projected.get("d"));
        assertEquals("EPriorSaid", projected.get("p"));
        assertTrue(projected.get("a") instanceof Map<?, ?>, "the payload must survive as a map");
        assertTrue(projected.get("e") instanceof Map<?, ?>, "embeds must survive as a map");
    }

    /**
     * {@code SignifyClientConfig#getAvailableWitnesses} (and its twin in {@code blockchain_publisher})
     * reads {@code iurls} off this to pick the witness set for a new agent AID. It used to cast the
     * result to {@code Map} — which throws the moment an AID actually has to be created.
     */
    @Test
    void theAgentConfigIsATypedModelNotAMap() throws Exception {
        assertEquals(AgentConfig.class, Coring.Config.class.getMethod("get").getReturnType());

        AgentConfig config = new AgentConfig().iurls(List.of("http://witness.example/oobi/BWITNESS/controller"));
        assertNotNull(config.getIurls());
        assertEquals(1, config.getIurls().size());
    }

    /**
     * The attestation floor and the bounded anchor scan are both derived from a key state's sequence.
     * {@code query} is the NETWORK refresh and {@code get} is the local read — the sequence must be
     * taken off the latter, and only after the former has run, or the agent answers from a view that
     * predates the event the wallet just signed.
     */
    @Test
    void aKeyStateIsReadBackTypedFromTheLocalGetNotFromTheQueryOperation() throws Exception {
        assertEquals("java.util.Optional<org.cardanofoundation.signify.generated.keria.model.KeyStateRecord>",
                KeyStates.class.getMethod("get", String.class).getGenericReturnType().getTypeName());

        // query() answers with an operation to wait on, never with the state itself.
        assertEquals("org.cardanofoundation.signify.generated.keria.model.QueryOperation",
                KeyStates.class.getMethod("query", String.class, String.class).getReturnType().getName());
    }
}
