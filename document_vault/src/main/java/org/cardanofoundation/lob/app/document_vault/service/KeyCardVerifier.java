package org.cardanofoundation.lob.app.document_vault.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Validates permissionless key cards. There is no issuer and no signature, so a card is accepted on
 * shape alone and this checks only what the backend can guarantee by itself: a supported version and
 * type, and no private-key material. Identity binding is out-of-band, by the sender.
 *
 * <p>Deliberately not checked: which organisation the card names. {@code subject.organisationId} is
 * the holder's own, free-form organisation and has nothing to do with the addressbook the card is
 * imported into — that comes from the request, authorised against the caller's JWT in
 * {@link CardImportService}. Comparing the two would only compare client input against client input,
 * and would make externally minted cards unimportable.
 */
@Component
public class KeyCardVerifier {

    private static final int SUPPORTED_CARD_VERSION = 1;
    private static final String CARD_TYPE = "REEVE_KEY_CARD";
    /** Names a card must never carry. Matched case-insensitively and ignoring separators, so
     *  {@code private_key}, {@code PrivateKey} and {@code privatekey} are all the same field. */
    private static final List<String> SECRET_FIELD_NAMES = List.of("privatekey", "secretkey", "seed",
            "mnemonic", "passphrase", "scalar");

    public Either<ProblemDetail, KeyCardDto> verify(KeyCardDto card) {
        if (card.getV() != SUPPORTED_CARD_VERSION || !CARD_TYPE.equals(card.getType())) {
            return Either.left(VaultProblems.badRequest(VaultProblems.UNSUPPORTED_CARD_VERSION,
                    "Unsupported key card: type=%s v=%d (this server understands %s v%d)."
                            .formatted(card.getType(), card.getV(), CARD_TYPE, SUPPORTED_CARD_VERSION)));
        }
        // I5: the backend must never hold private key material. Checked regardless of anything else, so
        // a card full of key material is rejected outright. Nested objects cannot smuggle one past this:
        // KeyCardDto's subject and key records reject unknown fields outright, so anything secret-shaped
        // lands either here or in a deserialisation failure.
        Optional<String> secret = card.getUnknown().keySet().stream().filter(KeyCardVerifier::isSecretName)
                .findFirst();
        if (secret.isPresent()) {
            return Either.left(VaultProblems.badRequest(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                    ("This card still carries a '%s' field. Strip it in the client before importing: the "
                            + "server must never hold private key material.").formatted(secret.get())));
        }
        return Either.right(card);
    }

    private static boolean isSecretName(String field) {
        String normalised = field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SECRET_FIELD_NAMES.contains(normalised);
    }
}
