package org.cardanofoundation.lob.app.support.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.AES_TRANSFORMATION;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.IV_LENGTH_BYTES;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.RSA_TRANSFORMATION;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.TAG_LENGTH_BITS;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.aad;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.decodeKeyMaterial;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.oaepParameters;
import static org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor.requireRsaStrength;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hybrid RSA-OAEP + AES-256-GCM decryptor — the decrypt-only half held by the netsuite tier.
 * <p>
 * Failure modes are deliberately split so operators are not misled (details stay in logs, never
 * HTTP responses): an RSA unwrap failure means the tiers hold mismatched keys; a GCM tag failure
 * means the ciphertext was tampered with or bound to a different organisation.
 */
public class RsaOaepHybridDecryptor implements SecretDecryptor {

    /** Smallest parseable body: length field + wrapped key + IV + bare GCM tag. */
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;

    private final PrivateKey privateKey;
    private final int modulusBytes;

    public RsaOaepHybridDecryptor(String base64PrivateKey) {
        byte[] der = decodeKeyMaterial(base64PrivateKey, "private");

        PrivateKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Config encryption private key is not a valid PKCS#8 RSA key.", e);
        }

        this.modulusBytes = requireRsaStrength(key, "private");
        this.privateKey = key;
    }

    @Override
    public String decrypt(String envelope, String organisationId) {
        if (envelope == null || envelope.isBlank()) {
            throw new IllegalArgumentException("Secret envelope is empty");
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(envelope);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Secret envelope is not valid Base64", e);
        }

        if (raw.length < 2) {
            throw new IllegalStateException("Secret envelope is truncated");
        }

        int wrappedKeyLength = ((raw[0] & 0xff) << 8) | (raw[1] & 0xff);
        if (wrappedKeyLength == 0) {
            throw new IllegalStateException("Secret envelope declares an empty key block");
        }
        if (wrappedKeyLength != modulusBytes) {
            throw new IllegalStateException(
                    ("Secret envelope key block length %d does not match the configured key (%d bytes) — "
                            + "key-pair mismatch between tiers (different key sizes) or corrupted envelope")
                            .formatted(wrappedKeyLength, modulusBytes));
        }
        if (raw.length < 2 + wrappedKeyLength + IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw new IllegalStateException("Secret envelope is truncated");
        }

        byte[] wrappedKey = Arrays.copyOfRange(raw, 2, 2 + wrappedKeyLength);
        byte[] iv = Arrays.copyOfRange(raw, 2 + wrappedKeyLength, 2 + wrappedKeyLength + IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(raw, 2 + wrappedKeyLength + IV_LENGTH_BYTES, raw.length);

        byte[] dataKey;
        try {
            Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
            rsa.init(Cipher.DECRYPT_MODE, privateKey, oaepParameters());
            dataKey = rsa.doFinal(wrappedKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Secret envelope not decryptable with this private key — "
                            + "key-pair mismatch between tiers or corrupted key block", e);
        }

        if (dataKey.length != RsaOaepHybridEncryptor.DATA_KEY_LENGTH_BYTES) {
            // Only a public-key holder can mint such an envelope, but an empty or odd-sized key
            // would otherwise surface as an IllegalArgumentException from SecretKeySpec below.
            Arrays.fill(dataKey, (byte) 0);
            throw new IllegalStateException("Secret envelope carries a data key of unexpected size");
        }

        try {
            Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            aes.updateAAD(aad(organisationId));

            return new String(aes.doFinal(ciphertext), UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Secret envelope ciphertext tampered or organisation context mismatch", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret value", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

}
