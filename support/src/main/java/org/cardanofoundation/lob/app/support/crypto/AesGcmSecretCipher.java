package org.cardanofoundation.lob.app.support.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit IV per encryption.
 * <p>
 * Envelope: {@code v1:} + Base64(iv ‖ ciphertext ‖ tag). The version prefix exists so a future
 * key rotation can introduce {@code v2:} and still decrypt {@code v1:} values.
 */
public class AesGcmSecretCipher implements SecretCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(
                    "Config encryption key is not set. Provide lob.security.config-encryption.key (LOB_CONFIG_ENCRYPTION_KEY).");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Config encryption key is not valid Base64.", e);
        }

        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "Config encryption key must decode to exactly 32 bytes, got %d.".formatted(keyBytes.length));
        }

        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));

            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret value", e);
        }
    }

    @Override
    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("Unsupported secret envelope version");
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(envelope.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Secret envelope is not valid Base64", e);
        }

        if (raw.length <= IV_LENGTH_BYTES) {
            throw new IllegalStateException("Secret envelope is truncated");
        }

        byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = new byte[raw.length - IV_LENGTH_BYTES];
        System.arraycopy(raw, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt secret value — wrong key or tampered data", e);
        }
    }

}
