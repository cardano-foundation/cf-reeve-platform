package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.service.KelAnchorVerifier.AnchorCandidate;
import org.cardanofoundation.signify.exception.SignifyInterruptedException;

/**
 * Synchronous, no-ceremony verification of a Veridian-attested card at import time.
 *
 * <p>A card produced by an issuing indexer's card-attestation ceremony carries an {@code attestation}
 * block; before the platform trusts such a card it re-verifies, from first principles, that:
 * <ol>
 *   <li><b>the AID is real</b> — its OOBI resolves on our KERIA ({@link KeriOobiService#refreshResolve});</li>
 *   <li><b>the credential is genuine</b> — the presented CESR chain validates as a non-revoked
 *       credential of the claimed schema issued to {@code aid} ({@link CredentialChainValidator}),
 *       and its SAID/schema match what the card asserts;</li>
 *   <li><b>the attestation binds THIS card</b> — the card's digest, recomputed from the card body,
 *       yields a remotesign payload SAID that the attesting AID sealed in the KEL event the card
 *       names ({@link KelAnchorVerifier}).</li>
 * </ol>
 *
 * <p>Step 3 reads KERI, not the chain. The issuing ceremony publishes no transaction — the wallet's
 * KEL interaction event IS the attestation — so there is nothing on-chain to check and this path needs
 * no Cardano access at all. That is what lets an attested card be imported on a tier deployed without
 * a chain client.
 *
 * <p>Everything the card asserts about its own digest and payload SAID is treated as a hint for a
 * better error message and never as an input: both are recomputed, and only the recomputed payload
 * SAID is looked for in the KEL. The one card-supplied value that must be trusted as given is
 * {@code metadataLabel}, because it is an input to the SAID the wallet actually signed — assuming the
 * default there would fail every deployment that configured a different label.
 *
 * <p><b>SECURITY / TODO(policy):</b> the KEL seal proves that {@code aid} attested this exact card
 * body. It does not bind which credential was presented: {@code credentialSaid} and {@code schemaSaid}
 * are outside the signed payload, and are instead checked against the CESR chain, which the validator
 * pins to {@code aid} as issuee. Binding them into the signed payload is an issuer-side wire-format
 * change to a shape live wallets are sensitive to, so it is deliberately not attempted here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class AttestationImportVerifier {

    public static final String CARD_ATTESTATION_INVALID = "CARD_ATTESTATION_INVALID";
    public static final String CARD_ATTESTATION_UNVERIFIABLE = "CARD_ATTESTATION_UNVERIFIABLE";

    private final KeriOobiService oobiService;
    private final CredentialChainValidator chainValidator;
    private final KelAnchorVerifier anchorVerifier;
    private final RemotesignRequestFactory kedFactory;
    private final SchemaOobiResolver schemaOobiResolver;

    /**
     * The card-attestation fields carried on an imported card, plus the digest the platform recomputed
     * from the card body.
     *
     * @param assertedCardDigest  the issuer's own claim of the digest, compared against
     *                            {@code recomputedCardDigest} only to distinguish "this card was
     *                            altered" from "this card was never attested".
     */
    public record CardAttestationClaim(String oobi, String aid, String credentialSaid, String schemaSaid,
            String kelSequence, String kelEventSaid, String metadataLabel, String assertedCardDigest,
            String assertedPayloadSaid, String credentialCesr, String recomputedCardDigest) {
    }

    /**
     * @return {@link Either#right} when every check passes; {@link Either#left} with the failing
     *         {@link ProblemDetail} otherwise (bad OOBI / rejected credential / unanchored or
     *         mismatched attestation / unverifiable when KERI cannot be reached).
     */
    public Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> verify(String userId,
            CardAttestationClaim claim) {
        // 1. OOBI resolves -> the AID is a real, reachable KERI identity, and our agent's view of its
        //    KEL is refreshed before step 3 reads it.
        Either<ProblemDetail, Void> resolved = oobiService.refreshResolve(userId, claim.oobi(), claim.aid());
        if (resolved.isLeft()) {
            return Either.left(resolved.getLeft());
        }

        // 2. The presented credential chain is a valid, non-revoked credential of an accepted schema
        //    issued to this AID by an issuer this deployment trusts. The configured issuer OOBIs are
        //    resolved first: without them the agent cannot answer for the issuer's registry, and a
        //    genuine credential would be rejected for want of an introduction.
        schemaOobiResolver.ensureResolved();
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> credential = verifyCredential(claim);
        if (credential.isLeft()) {
            return credential;
        }

        // 3. The attesting AID sealed this exact card in its KEL.
        Either<ProblemDetail, Void> anchored = verifyAnchor(claim);
        return anchored.isLeft() ? Either.left(anchored.getLeft()) : Either.right(credential.get());
    }

    private Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> verifyCredential(
            CardAttestationClaim claim) {
        if (claim.credentialCesr() == null || claim.credentialCesr().isBlank()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The attested card carries no credential chain (credentialCesr) to verify."));
        }
        // Both are what identify the leaf, so a card that names neither cannot be verified — the
        // validator would otherwise be free to pick whichever credential in the stream happened to pass.
        if (claim.credentialSaid() == null || claim.credentialSaid().isBlank()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The attested card names no credentialSaid, so its credential cannot be identified."));
        }
        try {
            return chainValidator.validate(claim.credentialCesr(), claim.aid(), claim.credentialSaid(),
                    claim.schemaSaid());
        } catch (RuntimeException e) {
            // A hostile chain can throw rather than return a rejection; KeriCredentialService wraps its
            // own call for the same reason.
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The card's credential chain could not be processed: " + e.getMessage()));
        }
    }

    /**
     * Recomputes the payload SAID the attesting wallet would have signed for this card body, and
     * requires the KEL event the card names to seal it.
     */
    private Either<ProblemDetail, Void> verifyAnchor(CardAttestationClaim claim) {
        if (claim.metadataLabel() == null || claim.metadataLabel().isBlank()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The attested card names no metadataLabel, without which the anchored payload SAID "
                            + "cannot be reproduced."));
        }
        AnchorCandidate candidate = new AnchorCandidate(claim.kelEventSaid(), claim.kelSequence());
        if (candidate.isEmpty()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The attested card names no KEL anchor (kelSequence/kelEventSaid) to verify against."));
        }
        if (claim.assertedCardDigest() != null && !claim.assertedCardDigest().equals(claim.recomputedCardDigest())) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    ("The card's asserted digest (%s) does not match the digest recomputed from its body (%s) — "
                            + "the card was altered after it was attested.")
                            .formatted(claim.assertedCardDigest(), claim.recomputedCardDigest())));
        }

        String payloadSaid;
        try {
            payloadSaid = (String) kedFactory
                    .anchorRequestKed(claim.aid(), claim.metadataLabel(), claim.recomputedCardDigest())
                    .get("d");
        } catch (RuntimeException e) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "Could not derive the attestation payload SAID for this card: " + e.getMessage()));
        }
        if (claim.assertedPayloadSaid() != null && !claim.assertedPayloadSaid().equals(payloadSaid)) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    ("The card's asserted payloadSaid (%s) does not match the one derived from its body (%s) — "
                            + "the card was altered after it was attested, or attested under a different label.")
                            .formatted(claim.assertedPayloadSaid(), payloadSaid)));
        }

        Optional<Map<String, Object>> event;
        try {
            event = anchorVerifier.findNamedAnchor(claim.aid(), candidate, payloadSaid);
        } catch (Exception e) {
            interruptIfNeeded(e);
            return Either.left(KeriAttestationProblems.serviceUnavailable(CARD_ATTESTATION_UNVERIFIABLE,
                    "Could not read the attesting AID's KEL to verify the anchor: " + e.getMessage()));
        }
        if (event.isEmpty()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    ("The KEL event this card names (seq=%s, said=%s) does not exist on AID %s or does not "
                            + "anchor this card's attestation (payload SAID %s).")
                            .formatted(claim.kelSequence(), claim.kelEventSaid(), claim.aid(), payloadSaid)));
        }

        log.info("card attestation verified: aid={} schema={} kelSeq={} kelEvent={}", claim.aid(),
                claim.schemaSaid(), claim.kelSequence(), claim.kelEventSaid());
        return Either.right(null);
    }

    /**
     * Restores the interrupt flag when {@code e} is an interruption in EITHER form.
     *
     * <p>Both kinds have to be named. {@code Thread.sleep} still raises the checked
     * {@link InterruptedException}, but a signify client call now wraps one in
     * {@link SignifyInterruptedException} — which extends {@code RuntimeException}, not
     * {@code InterruptedException}. Testing only the checked type therefore matches nothing the client
     * throws any more: the interrupt is caught by the surrounding {@code catch (Exception e)}, reported
     * as an ordinary step failure, and the flag is silently dropped — so a caller polling or sleeping
     * afterwards keeps going to its full timeout instead of stopping.
     */
    private static void interruptIfNeeded(Exception e) {
        if (e instanceof InterruptedException || e instanceof SignifyInterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
