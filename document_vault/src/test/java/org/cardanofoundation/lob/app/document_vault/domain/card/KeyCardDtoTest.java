package org.cardanofoundation.lob.app.document_vault.domain.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The card-format contract: an indexer-attested
 * {@code REEVE_KEY_CARD} carries a top-level {@code attestation} block; an unattested card omits it
 * entirely and must still parse exactly as before. Because {@link KeyCardDto#attestation} is now a
 * named field, Jackson must bind it there rather than routing it through
 * {@link KeyCardDto#putUnknown(String, Object)} — that catch-all exists to catch fields the DTO does
 * NOT model (and, per I5, to reject a stray {@code privateKey} section), so a modeled
 * {@code attestation} object landing in it instead would both hide the data and defeat that guard.
 */
class KeyCardDtoTest {

    private static final String CARD_WITHOUT_ATTESTATION = """
            {
              "v": 1, "type": "REEVE_KEY_CARD",
              "subject": {
                "subjectType": "EXTERNAL", "subjectId": "indexer-uuid-1",
                "displayName": "Bob Miller", "email": "bob@example.org", "organisationId": "Privat"
              },
              "key": {
                "publicKey": "%s", "label": "Bob's audit key", "assurance": "PORTABLE",
                "createdAt": "2026-07-14T10:15:30Z"
              }
            }
            """.formatted("a".repeat(64));

    private static final String CARD_WITH_ATTESTATION = """
            {
              "v": 1, "type": "REEVE_KEY_CARD",
              "subject": {
                "subjectType": "EXTERNAL", "subjectId": "indexer-uuid-1",
                "displayName": "Bob Miller", "email": "bob@example.org", "organisationId": "Privat"
              },
              "key": {
                "publicKey": "%s", "label": "Bob's audit key", "assurance": "PORTABLE",
                "createdAt": "2026-07-14T10:15:30Z"
              },
              "attestation": {
                "oobi": "https://example.org/oobi/EWalletAid/agent/EAgentEid",
                "aid": "EWalletAid",
                "credentialSaid": "ECredentialSaid",
                "schemaSaid": "ESchemaSaid",
                "txHash": "deadbeef"
              }
            }
            """.formatted("a".repeat(64));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anUnattestedCardParsesWithNoAttestationBlockAndAnEmptyUnknownMap() throws Exception {
        KeyCardDto card = objectMapper.readValue(CARD_WITHOUT_ATTESTATION, KeyCardDto.class);

        assertNull(card.getAttestation());
        assertFalse(card.getUnknown().containsKey("attestation"));
    }

    /**
     * Precedence: a named field wins over
     * {@code @JsonAnySetter} in Jackson, so simply modeling {@code attestation} is enough — no manual
     * dispatch needed. This test is the proof, not the assumption.
     */
    @Test
    void anAttestedCardParsesTheAttestationBlockIntoTheNamedFieldNotTheUnknownMap() throws Exception {
        KeyCardDto card = objectMapper.readValue(CARD_WITH_ATTESTATION, KeyCardDto.class);

        assertFalse(card.getUnknown().containsKey("attestation"),
                "attestation must bind to the named field, not fall through to @JsonAnySetter");
        KeyCardDto.CardAttestation attestation = card.getAttestation();
        assertEquals("https://example.org/oobi/EWalletAid/agent/EAgentEid", attestation.oobi());
        assertEquals("EWalletAid", attestation.aid());
        assertEquals("ECredentialSaid", attestation.credentialSaid());
        assertEquals("ESchemaSaid", attestation.schemaSaid());
        assertEquals("deadbeef", attestation.txHash());
    }
}
