package org.cardanofoundation.lob.app.document_vault.service;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Validates permissionless key cards (contract §2.8). There is no issuer and no signature — a card is
 * accepted on shape alone, so this checks only what the backend can actually guarantee by itself: a
 * supported version/type, and no private-key material (I5). Identity binding is out-of-band; the sender
 * verifies the recipient's key.
 *
 * Deliberately NOT checked: which organisation the card names. {@code subject.organisationId} is the
 * HOLDER's own organisation — a free-form label like "Privat" on a card minted outside Reeve — and has
 * nothing to do with which addressbook the card is being imported into. That is decided by the request,
 * whose organisation is authorised against the caller's JWT in {@link CardImportService}. The two were
 * once compared, a leftover from the signed-card design where the field was issuer-attested; with no
 * signature it only ever compared client input against client input, and it made external cards
 * unimportable.
 */
@Component
public class KeyCardVerifier {

    private static final int SUPPORTED_CARD_VERSION = 1;
    private static final String CARD_TYPE = "REEVE_KEY_CARD";
    private static final String PRIVATE_KEY_FIELD = "privateKey";

    public Either<ProblemDetail, KeyCardDto> verify(KeyCardDto card) {
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
        return Either.right(card);
    }
}
