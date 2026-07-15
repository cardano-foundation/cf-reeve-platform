package org.cardanofoundation.lob.app.document_vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.document_vault.domain.enums.CardSubjectType;
import org.cardanofoundation.lob.app.document_vault.domain.enums.KeyAssurance;

/**
 * The import is permissionless (contract §2.8, amended): there is no issuer and no signature.
 * {@link KeyCardVerifier} checks only what the backend must still guarantee on its own: a
 * supported version/type, no private-key material (I5), and that the card names the organisation
 * it is being imported into.
 */
class KeyCardVerifierTest {

    private static final String X25519_PUB = "a".repeat(64);

    private KeyCardVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new KeyCardVerifier();
    }

    private KeyCardDto card() {
        KeyCardDto card = new KeyCardDto();
        card.setV(1);
        card.setType("REEVE_KEY_CARD");
        card.setSubject(new KeyCardDto.Subject(CardSubjectType.REEVE_ACCOUNT, "sub-bob",
                "Bob Miller", "bob@example.org", "org1"));
        card.setKey(new KeyCardDto.Key(X25519_PUB, "Bob's audit key", KeyAssurance.PORTABLE,
                "2026-07-14T10:15:30Z"));
        return card;
    }

    @Test
    void acceptsAValidCard() {
        assertTrue(verifier.verify(card(), "org1").isRight());
    }

    @Test
    void rejectsAnUnsupportedCardVersion() {
        KeyCardDto card = card();
        card.setV(2);

        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card, "org1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION, result.getLeft().getTitle());
    }

    @Test
    void rejectsAnUnsupportedCardType() {
        KeyCardDto card = card();
        card.setType("SOMETHING_ELSE");

        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card, "org1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.UNSUPPORTED_CARD_VERSION, result.getLeft().getTitle());
    }

    /** I5: a private key must never enter the backend — not even wrapped. Reject, never silently drop. */
    @Test
    void rejectsACardStillCarryingItsPrivateKeySection() {
        KeyCardDto card = card();
        card.putUnknown("privateKey", Map.of("wrapped", "deadbeef"));

        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card, "org1");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_CONTAINS_PRIVATE_KEY, result.getLeft().getTitle());
    }

    @Test
    void rejectsACardIssuedForAnotherOrganisation() {
        Either<ProblemDetail, KeyCardDto> result = verifier.verify(card(), "other-org");

        assertTrue(result.isLeft());
        assertEquals(VaultProblems.CARD_ORG_MISMATCH, result.getLeft().getTitle());
    }
}
