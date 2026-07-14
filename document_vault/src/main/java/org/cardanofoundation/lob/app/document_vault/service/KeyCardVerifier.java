package org.cardanofoundation.lob.app.document_vault.service;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.Nullable;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 *
 * NOTE: this is the Task 4 slice of the class — {@code isTrustedIssuer} is the only member Task 4's
 * VaultKeyService needs. Task 4a (key cards, contract §2.8.2/§2.8.3) extends this same class with
 * {@code hasIssuers()}, {@code verify(KeyCardDto, String)} and {@code signingInput(KeyCardDto)}; the
 * constructor and issuer-parsing stay exactly as they are here so that addition does not touch this
 * task's tested surface.
 */
@Slf4j
@Component
public class KeyCardVerifier {

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
}
