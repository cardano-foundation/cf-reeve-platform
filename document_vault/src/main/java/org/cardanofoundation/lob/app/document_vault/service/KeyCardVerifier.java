package org.cardanofoundation.lob.app.document_vault.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nullable;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.vavr.control.Either;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;

/**
 * Verifies key cards (contract §2.8). BouncyCastle is already a platform-wide dependency (root
 * build.gradle.kts), and its low-level Ed25519Signer takes raw 32-byte keys — no X.509 encoding
 * dance, which is what makes hex-encoded keys straightforward here.
 *
 * Issuers are configured as a comma-separated `issuerId:publicKeyHex` list. A plain @Value string
 * rather than @ConfigurationProperties on purpose: the platform's property names contain underscores
 * (`lob.document_vault.*`), and @ConfigurationProperties prefixes may not — so @Value keeps the
 * naming consistent with every other module instead of introducing a lone hyphenated outlier.
 *
 * Empty list = no issuer this deployment trusts = card import is off (503), exactly as "no IPFS
 * configured" means publishing is off.
 */
@Slf4j
@Component
public class KeyCardVerifier {

    private static final int SUPPORTED_CARD_VERSION = 1;
    private static final String CARD_TYPE = "REEVE_KEY_CARD";
    private static final String SUPPORTED_ALGORITHM = "Ed25519";
    private static final String PRIVATE_KEY_FIELD = "privateKey";

    /** issuerId -> Ed25519 public key (lowercase hex). */
    private final Map<String, String> issuers;

    public KeyCardVerifier(@Value("${lob.document_vault.card.issuers:}") String rawIssuers) {
        this.issuers = parse(rawIssuers);
        log.info("Document vault key-card issuers configured: {}", issuers.keySet());
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return Map.copyOf(parsed);
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            // Fail fast at startup: a malformed issuer entry would silently disable a trust anchor,
            // and a deployment that thinks it trusts an issuer but does not is worse than one that
            // refuses to boot.
            if (parts.length != 2 || parts[0].isBlank() || !parts[1].matches("^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "Invalid lob.document_vault.card.issuers entry (expected id:64-hex-ed25519-key): "
                                + trimmed);
            }
            parsed.put(parts[0], parts[1]);
        }
        return Map.copyOf(parsed);
    }

    public boolean hasIssuers() {
        return !issuers.isEmpty();
    }

    /**
     * The kill switch (contract §2.8.5). A key is only as trustworthy as the issuer still vouching
     * for it: drop a compromised issuer from the config and every key it ever introduced stops being
     * offered as a wrap target — no revocation endpoint, no status column, no migration.
     *
     * SELF_ENROLLED keys have no issuer and are always trusted (issuerId == null): they were born on
     * their owner's device and no third party ever vouched for them.
     */
    public boolean isTrustedIssuer(@Nullable String issuerId) {
        return issuerId == null || issuers.containsKey(issuerId);
    }

    public Either<ProblemDetail, KeyCardDto> verify(KeyCardDto card, String organisationId) {
        // The algorithm is a SIGNED field, so a mismatch cannot be a silent downgrade — but it must
        // still be checked, or a card could name (say) "RSA" while we verify it as Ed25519 and the
        // holder would believe a guarantee we never made.
        if (card.getV() != SUPPORTED_CARD_VERSION
                || !CARD_TYPE.equals(card.getType())
                || !SUPPORTED_ALGORITHM.equals(card.getIssuer().algorithm())) {
            return Either.left(VaultProblems.badRequest(VaultProblems.UNSUPPORTED_CARD_VERSION,
                    "Unsupported key card: type=%s v=%d algorithm=%s (this server understands %s v%d, %s)."
                            .formatted(card.getType(), card.getV(), card.getIssuer().algorithm(),
                                    CARD_TYPE, SUPPORTED_CARD_VERSION, SUPPORTED_ALGORITHM)));
        }
        if (card.getUnknown().containsKey(PRIVATE_KEY_FIELD)) {
            return Either.left(VaultProblems.badRequest(VaultProblems.CARD_CONTAINS_PRIVATE_KEY,
                    "This card still carries its privateKey section. Strip it in the client before "
                            + "importing: the server must never hold private key material."));
        }

        String expectedIssuerKey = issuers.get(card.getIssuer().issuerId());
        if (expectedIssuerKey == null || !expectedIssuerKey.equals(card.getIssuer().publicKey())) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_ISSUER_UNKNOWN,
                    "Issuer %s is not trusted by this deployment.".formatted(card.getIssuer().issuerId())));
        }
        if (!verifySignature(card, expectedIssuerKey)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_SIGNATURE_INVALID,
                    "The card's signature does not verify — it is corrupt or forged."));
        }
        if (!card.getSubject().organisationId().equals(organisationId)) {
            return Either.left(VaultProblems.unprocessable(VaultProblems.CARD_ORG_MISMATCH,
                    "The card was issued for organisation %s, not %s."
                            .formatted(card.getSubject().organisationId(), organisationId)));
        }
        return Either.right(card);
    }

    private boolean verifySignature(KeyCardDto card, String issuerPublicKeyHex) {
        try {
            Ed25519PublicKeyParameters publicKey =
                    new Ed25519PublicKeyParameters(HexFormat.of().parseHex(issuerPublicKeyHex), 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, publicKey);
            byte[] input = signingInput(card);
            signer.update(input, 0, input.length);
            return signer.verifySignature(HexFormat.of().parseHex(card.getSignature()));
        } catch (IllegalArgumentException e) { // malformed hex slipped past bean validation
            log.warn("Malformed key card signature material: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Contract §2.8.3 — the exact bytes three implementations must agree on (this verifier, the
     * Indexer's issuer, the frontend's importer).
     *
     * Length-prefixed concatenation rather than canonical JSON, deliberately: it removes every
     * canonicalisation question (key order, whitespace, unicode escaping) that JSON would force all
     * three to answer identically. Each field is its 4-byte big-endian UTF-8 length, then its bytes.
     * Changing this list means a new card version — never an in-place edit.
     */
    static byte[] signingInput(KeyCardDto card) {
        // CARD_TYPE is hardcoded rather than card.getType() — safe only because verify() rejects any
        // card whose type != CARD_TYPE before this is ever called. Callers of this method must have
        // already validated that invariant.
        List<String> fields = List.of(
                CARD_TYPE,
                String.valueOf(card.getV()),
                card.getSubject().subjectType().name(),
                card.getSubject().subjectId(),
                card.getSubject().displayName(),
                card.getSubject().email(),
                card.getSubject().organisationId(),
                card.getKey().publicKey(),
                card.getKey().label(),
                card.getKey().assurance().name(),
                card.getKey().createdAt(),
                card.getIssuer().issuerId(),
                card.getIssuer().algorithm(),
                card.getIssuer().publicKey());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
            out.write(ByteBuffer.allocate(4).putInt(bytes.length).array(), 0, 4);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }
}
