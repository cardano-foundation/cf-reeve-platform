package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a recipient's PUBLIC, on-chain-publishable identifier from their X25519 public key:
 *
 * <pre>recipient_key_hash = sha256( 32 raw bytes decoded from the lowercase-hex public key )</pre>
 *
 * <p>rendered as 64 lowercase hex characters. No salt, no domain-separation prefix, no truncation -
 * this is a public permissionless format, and anyone auditing a document from a block explorer must
 * be able to reproduce a hash with {@code printf %s <pubkey> | xxd -r -p | sha256sum} and nothing
 * else. SHA-256 rather than the SHA3-256 used for the organisation id because WebCrypto implements
 * no SHA-3 member, and the Indexer frontend recomputes this in the browser.
 *
 * <p><b>Publishing this makes a document permanently linkable to its recipients</b> - see
 * docs/onChainFormat.md "Recipient key hashes". That is a deliberate, accepted trade-off in exchange
 * for recipient-side filtering in the public Indexer, not an oversight.
 *
 * <p>Not annotated {@code @Service}: a pure static function with no state and no dependencies,
 * matching how {@code Cip170MetadataFactory}'s static helpers are used.
 */
public final class RecipientKeyHasher {

    /** X25519 public keys are 32 bytes, so 64 hex characters. */
    private static final int PUBLIC_KEY_HEX_LENGTH = 64;

    private RecipientKeyHasher() {
    }

    public static String hash(String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.length() != PUBLIC_KEY_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "X25519 public key must be %d hex characters, got: %s"
                            .formatted(PUBLIC_KEY_HEX_LENGTH,
                                    publicKeyHex == null ? "null" : String.valueOf(publicKeyHex.length())));
        }
        byte[] publicKey;
        try {
            // Decode FIRST: hashing the hex string instead of the bytes it denotes would produce a
            // plausible-looking but wrong digest that no other implementation would agree with.
            publicKey = HexFormat.of().parseHex(publicKeyHex.toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("X25519 public key is not valid hex: " + e.getMessage(), e);
        }

        return HexFormat.of().formatHex(sha256(publicKey));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
