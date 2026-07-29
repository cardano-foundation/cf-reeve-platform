package org.cardanofoundation.lob.app.keri_attestation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

/**
 * Synchronous, no-ceremony verification of a Veridian-attested card at import time.
 * A card produced by the indexer's card-attestation ceremony carries an {@code attestation}
 * block ({@code oobi}, {@code aid}, {@code credentialSaid}, {@code schemaSaid}, {@code txHash},
 * {@code credentialCesr}); before the platform trusts such a card it re-verifies, from first
 * principles, that:
 * <ol>
 *   <li><b>the AID is real</b> — its OOBI resolves on our KERIA ({@link KeriOobiService#refreshResolve});</li>
 *   <li><b>the credential is genuine</b> — the presented CESR chain validates as a non-revoked
 *       credential of the claimed schema issued to {@code aid} ({@link CredentialChainValidator}),
 *       and its SAID/schema match what the card asserts;</li>
 *   <li><b>the attestation is on-chain and binds THIS card</b> — the CIP-170 label-170 metadata for
 *       {@code txHash} is a {@code t=ATTEST} entry by {@code i=aid} whose {@code d} equals the card's
 *       recomputed digest ({@code expectedCardDigestQb64}).</li>
 * </ol>
 * Read-only, no wallet interaction, no ceremony. Reuses the same primitives the ceremony uses.
 *
 * <p>The Cardano metadata reader ({@link CardanoMetadataReader}) is an {@link ObjectProvider}
 * because {@code blockchain_publisher} may be disabled; without it the on-chain check cannot run and
 * verification returns {@code unverifiable} rather than silently passing.
 *
 * <p><b>SECURITY / TODO(policy):</b> step 3 trusts the on-chain label-170 {@code i} and {@code d}
 * fields as read (raw tx metadata), exactly like the sibling AUTH_BEGIN external-verification path
 * ({@code KeriAuthBeginService#validateExternalMetadata}) and the module's deliberately-relaxed
 * credential policy. It does NOT yet re-verify that the ATTEST payload SAID ({@code d}) is anchored
 * in {@code aid}'s KEL (the seal the indexer's ceremony produces). Until that closure lands, a party
 * able to both replay a valid credential CESR issued to {@code aid} AND write plaintext
 * {@code {t:ATTEST, i:aid, d:<forged-card-digest>}} metadata on-chain could pass this check. Closing
 * it means, after the OOBI refresh in step 1, reading {@code aid}'s KEL and confirming an anchoring
 * event whose seal digest equals {@code d} — to be enabled together with the rest of the module's
 * authority policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lob.keri-attestation.keria", name = "url")
public class AttestationImportVerifier {

    public static final String CARD_ATTESTATION_INVALID = "CARD_ATTESTATION_INVALID";
    public static final String CARD_ATTESTATION_UNVERIFIABLE = "CARD_ATTESTATION_UNVERIFIABLE";

    private static final long ATTEST_METADATA_LABEL = 170L;

    private final KeriOobiService oobiService;
    private final CredentialChainValidator chainValidator;
    private final ObjectProvider<CardanoMetadataReader> metadataReaderProvider;
    private final KeriAttestationProperties properties;

    /** The card-attestation fields carried on an imported card, plus the recomputed card digest. */
    public record CardAttestationClaim(String oobi, String aid, String credentialSaid, String schemaSaid,
            String txHash, String credentialCesr, String expectedCardDigestQb64) {
    }

    /**
     * @return {@link Either#right} when every check passes; {@link Either#left} with the failing
     *         {@link ProblemDetail} otherwise (bad OOBI / rejected credential / attestation mismatch /
     *         unverifiable when the Cardano reader is absent).
     */
    public Either<ProblemDetail, Void> verify(String userId, CardAttestationClaim claim) {
        // 1. OOBI resolves -> the AID is a real, reachable KERI identity.
        Either<ProblemDetail, Void> resolved = oobiService.refreshResolve(userId, claim.oobi(), claim.aid());
        if (resolved.isLeft()) {
            return resolved;
        }

        // 2. The presented credential chain is a valid, non-revoked credential of the claimed schema
        //    issued to this AID. Requires the chain to be carried on the card.
        if (claim.credentialCesr() == null || claim.credentialCesr().isBlank()) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The attested card carries no credential chain (credentialCesr) to verify."));
        }
        List<String> allowedSchemas = properties.credentialPolicy() != null
                ? properties.credentialPolicy().schemaSaids() : List.of();
        List<String> trustedRoots = properties.credentialPolicy() != null
                ? properties.credentialPolicy().trustedRootAids() : List.of();
        Either<ProblemDetail, CredentialChainValidator.ValidatedCredential> validated =
                chainValidator.validate(claim.credentialCesr(), claim.aid(), allowedSchemas, trustedRoots);
        if (validated.isLeft()) {
            return Either.left(validated.getLeft());
        }
        CredentialChainValidator.ValidatedCredential vc = validated.get();
        if (claim.credentialSaid() != null && !claim.credentialSaid().equals(vc.credentialSaid())) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The card's asserted credentialSaid (%s) does not match the validated leaf (%s)."
                            .formatted(claim.credentialSaid(), vc.credentialSaid())));
        }
        if (claim.schemaSaid() != null && !claim.schemaSaid().equals(vc.schemaSaid())) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID,
                    "The card's asserted schemaSaid (%s) does not match the validated credential's (%s)."
                            .formatted(claim.schemaSaid(), vc.schemaSaid())));
        }

        // 3. The on-chain CIP-170 ATTEST binds this exact card (its digest) to this AID.
        //    NOTE(policy): trusts the metadata i/d as read; KEL-seal anchoring of d is not yet
        //    re-verified here (see class javadoc SECURITY note).
        CardanoMetadataReader metadataReader = metadataReaderProvider.getIfAvailable();
        if (metadataReader == null) {
            return Either.left(KeriAttestationProblems.serviceUnavailable(CARD_ATTESTATION_UNVERIFIABLE,
                    "No Cardano metadata reader available to verify the on-chain attestation."));
        }
        Optional<Map<String, Object>> metadataOpt;
        try {
            metadataOpt = metadataReader.readCip170Metadata(claim.txHash());
        } catch (Exception e) {
            return Either.left(KeriAttestationProblems.serviceUnavailable(CARD_ATTESTATION_UNVERIFIABLE,
                    "Failed to read CIP-170 metadata for tx %s: %s".formatted(claim.txHash(), e.getMessage())));
        }
        String rejection = validateAttestMetadata(metadataOpt, claim);
        if (rejection != null) {
            return Either.left(KeriAttestationProblems.unprocessable(CARD_ATTESTATION_INVALID, rejection));
        }

        log.info("card attestation verified: aid={} schema={} tx={}", claim.aid(), claim.schemaSaid(),
                claim.txHash());
        return Either.right(null);
    }

    /** @return a human-readable rejection reason, or {@code null} if the on-chain ATTEST verifies. */
    private static String validateAttestMetadata(Optional<Map<String, Object>> metadataOpt,
            CardAttestationClaim claim) {
        if (metadataOpt.isEmpty()) {
            return "No label-%d metadata found on-chain for tx %s.".formatted(ATTEST_METADATA_LABEL, claim.txHash());
        }
        Map<String, Object> metadata = metadataOpt.get();
        if (!"ATTEST".equals(metadata.get("t"))) {
            return "On-chain label-170 metadata is not an ATTEST entry (t=%s).".formatted(metadata.get("t"));
        }
        if (!claim.aid().equals(metadata.get("i"))) {
            return "On-chain ATTEST issuer AID (%s) does not match the card's attesting AID (%s)."
                    .formatted(metadata.get("i"), claim.aid());
        }
        if (!claim.expectedCardDigestQb64().equals(metadata.get("d"))) {
            return "On-chain ATTEST digest (%s) does not match the recomputed card digest (%s) — "
                    .formatted(metadata.get("d"), claim.expectedCardDigestQb64())
                    + "the card was altered after attestation or the tx attests a different card.";
        }
        return null;
    }
}
