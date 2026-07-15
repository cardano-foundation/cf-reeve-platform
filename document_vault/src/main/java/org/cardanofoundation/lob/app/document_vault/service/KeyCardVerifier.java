package org.cardanofoundation.lob.app.document_vault.service;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Validates permissionless key cards (contract §2.8). There is no issuer and no signature — a card is
 * accepted on shape alone, so this checks only what the backend must still guarantee: a supported
 * version/type, no private-key material (I5), and that the card names the organisation it is being
 * imported into. Identity binding is out-of-band; the sender verifies the recipient's key.
 */
@Component
public class KeyCardVerifier {

    private static final int SUPPORTED_CARD_VERSION = 1;
    private static final String CARD_TYPE = "REEVE_KEY_CARD";
    private static final String PRIVATE_KEY_FIELD = "privateKey";

    public Either<ProblemDetail, KeyCardDto> verify(KeyCardDto card, String organisationId) {
        if (card.getV() != SUPPORTED_CARD_VERSION || !CARD_TYPE.equals(card.getType())) {
            return Either.left(VaultProblems.badRequest(VaultProblems.UNSUPPORTED_CARD_VERSION,
                    "Unsupported key card: type=%s v=%d (this server understands %s v%d)."
                            .formatted(card.getType(), card.getV(), CARD_TYPE, SUPPORTED_CARD_VERSION)));
        }
        // I5: the backend must never hold private key material. Checked regardless of anything else, so
        // a card full of key material is rejected outright.
        if (card.getUnknown().containsKey(PRIVATE_KEY_FIELD)) {
            return Either.left(VaultProblems.badRequest(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                    "This card still carries its privateKey section. Strip it in the client before "
                            + "importing: the server must never hold private key material."));
        }
        if (!card.getSubject().organisationId().equals(organisationId)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_ORG_MISMATCH,
                    "The card was issued for organisation %s, not %s."
                            .formatted(card.getSubject().organisationId(), organisationId)));
        }
        return Either.right(card);
    }
}
