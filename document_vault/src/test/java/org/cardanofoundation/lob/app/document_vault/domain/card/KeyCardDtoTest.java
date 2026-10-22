package org.cardanofoundation.lob.app.document_vault.domain.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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
                "kelSequence": "3",
                "kelEventSaid": "EKelEventSaid",
                "metadataLabel": "170",
                "cardDigest": "ECardDigest",
                "payloadSaid": "EPayloadSaid"
              }
            }
            """.formatted("a".repeat(64));

    /** A card from before the KEL-anchor format, still carrying the retired on-chain tx hash. */
    private static final String CARD_WITH_LEGACY_TX_HASH_ATTESTATION = """
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

    /** The issuer omits a blank displayName, email and label rather than sending empty strings. */
    private static final String MINIMAL_CARD = """
            {
              "v": 1, "type": "REEVE_KEY_CARD",
              "subject": { "subjectType": "EXTERNAL", "subjectId": "indexer-uuid-1", "organisationId": "" },
              "key": { "publicKey": "%s", "assurance": "PORTABLE", "createdAt": "2026-07-14T10:15:30Z" }
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
        assertEquals("3", attestation.kelSequence());
        assertEquals("EKelEventSaid", attestation.kelEventSaid());
        assertEquals("170", attestation.metadataLabel());
        assertEquals("ECardDigest", attestation.cardDigest());
        assertEquals("EPayloadSaid", attestation.payloadSaid());
    }

    /**
     * A card issued before the KEL-anchor format still parses: its retired {@code txHash} is ignored
     * rather than rejected, so an old export does not become unreadable. It has no anchor to verify, so
     * verification rejects it later — that is a different, and honest, failure.
     */
    @Test
    void aLegacyCardCarryingTxHashStillParsesWithTheFieldIgnored() throws Exception {
        KeyCardDto card = objectMapper.readValue(CARD_WITH_LEGACY_TX_HASH_ATTESTATION, KeyCardDto.class);

        KeyCardDto.CardAttestation attestation = card.getAttestation();
        assertEquals("EWalletAid", attestation.aid());
        assertNull(attestation.kelSequence());
        assertNull(attestation.kelEventSaid());
        assertFalse(card.getUnknown().containsKey("attestation"));
    }

    /**
     * The issuer treats displayName, email and label as optional and omits them when blank. Requiring
     * them here would reject a genuine card before verification ever ran.
     *
     * <p>Runs the real Jakarta validator, not just the parse: parsing alone would pass even with the
     * old {@code @NotBlank} annotations still in place, so it would not have caught the defect this
     * pins.
     */
    @Test
    void aCardOmittingTheOptionalSubjectAndKeyFieldsParsesAndValidates() throws Exception {
        KeyCardDto card = objectMapper.readValue(MINIMAL_CARD, KeyCardDto.class);

        assertNull(card.getSubject().displayName());
        assertNull(card.getSubject().email());
        assertNull(card.getKey().label());
        assertEquals("indexer-uuid-1", card.getSubject().subjectId());
        assertTrue(card.getUnknown().isEmpty());

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<KeyCardDto>> violations = factory.getValidator().validate(card);
            assertTrue(violations.isEmpty(), () -> "a minimal card must satisfy bean validation, but got: "
                    + violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage()).toList());
        }
    }

    /** The fully-populated card must validate too, so the test above is not passing for a trivial reason. */
    @Test
    void aFullyPopulatedAttestedCardValidates() throws Exception {
        KeyCardDto card = objectMapper.readValue(CARD_WITH_ATTESTATION, KeyCardDto.class);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(card).isEmpty());
        }
    }

    /**
     * subject and key are covered by the attestation digest, so an unknown field there is either a
     * smuggled secret or proof the digest formula diverged. Both must fail loudly rather than be
     * dropped — a dropped {@code key.privateKey} would mean the server accepted a card carrying secret
     * material and simply did not mention it.
     */
    @Test
    void anUnknownFieldInsideKeyIsRejectedRatherThanSilentlyDropped() {
        String smuggled = """
                {
                  "v": 1, "type": "REEVE_KEY_CARD",
                  "subject": { "subjectType": "EXTERNAL", "subjectId": "x", "organisationId": "" },
                  "key": { "publicKey": "%s", "assurance": "PORTABLE", "createdAt": "2026-07-14T10:15:30Z",
                           "privateKey": "deadbeef" }
                }
                """.formatted("a".repeat(64));

        assertThrows(UnrecognizedPropertyException.class,
                () -> objectMapper.readValue(smuggled, KeyCardDto.class));
    }

    @Test
    void anUnknownFieldInsideSubjectIsRejected() {
        String smuggled = """
                {
                  "v": 1, "type": "REEVE_KEY_CARD",
                  "subject": { "subjectType": "EXTERNAL", "subjectId": "x", "organisationId": "", "scalar": "s" },
                  "key": { "publicKey": "%s", "assurance": "PORTABLE", "createdAt": "2026-07-14T10:15:30Z" }
                }
                """.formatted("a".repeat(64));

        assertThrows(UnrecognizedPropertyException.class,
                () -> objectMapper.readValue(smuggled, KeyCardDto.class));
    }
}
