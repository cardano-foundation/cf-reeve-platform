package org.cardanofoundation.lob.app.support.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hybrid RSA-OAEP + AES-256-GCM encryptor — the encrypt-only half held by the organisation tier.
 * <p>
 * Envelope: Base64( wrappedKeyLen[2 bytes BE, unsigned] ‖ RSA-OAEP(dataKey) ‖ iv[12] ‖
 * AES-256-GCM(plaintext, 128-bit tag) ). A fresh random data key and IV are drawn per call; the
 * plaintext data-key bytes are zeroed afterwards (best-effort only — the JVM gives no guarantee
 * about copies).
 * <p>
 * OAEP is pinned to SHA-256 for both the digest and MGF1 via an explicit
 * {@link OAEPParameterSpec}: the SunJCE transformation string
 * {@code OAEPWithSHA-256AndMGF1Padding} silently defaults MGF1 to SHA-1, which other providers do
 * not, and the resulting mismatch is indistinguishable from a wrong key.
 */
public class RsaOaepHybridEncryptor implements SecretEncryptor {

    static final String AAD_PREFIX = "reeve/netsuite.privateKey/";
    static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding";
    static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    static final int DATA_KEY_LENGTH_BYTES = 32;
    static final int IV_LENGTH_BYTES = 12;
    static final int TAG_LENGTH_BITS = 128;
    static final int MIN_RSA_BITS = 3072;

    static OAEPParameterSpec oaepParameters() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    static byte[] aad(String organisationId) {
        return (AAD_PREFIX + organisationId).getBytes(UTF_8);
    }

    /**
     * Decodes a Base64(DER) key env value. Whitespace and line breaks are stripped first because
     * {@code openssl base64} wraps lines and the strict JDK decoder rejects them.
     */
    static byte[] decodeKeyMaterial(String base64, String keyRole) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException(
                    "Config encryption %s key is not set.".formatted(keyRole));
        }

        try {
            return Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Config encryption %s key is not valid Base64.".formatted(keyRole), e);
        }
    }

    static int requireRsaStrength(java.security.Key key, String keyRole) {
        if (!(key instanceof java.security.interfaces.RSAKey rsaKey)) {
            throw new IllegalArgumentException(
                    "Config encryption %s key is not an RSA key.".formatted(keyRole));
        }

        int bits = rsaKey.getModulus().bitLength();
        if (bits < MIN_RSA_BITS) {
            throw new IllegalArgumentException(
                    "Config encryption %s key must be at least %d bits, got %d."
                            .formatted(keyRole, MIN_RSA_BITS, bits));
        }

        return (bits + 7) / 8;
    }

    private final RSAPublicKey publicKey;
    private final SecureRandom random = new SecureRandom();

    public RsaOaepHybridEncryptor(String base64PublicKey) {
        byte[] der = decodeKeyMaterial(base64PublicKey, "public");

        PublicKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Config encryption public key is not a valid X.509 RSA key.", e);
        }

        requireRsaStrength(key, "public");
        this.publicKey = (RSAPublicKey) key;
    }

    @Override
    public String encrypt(String plaintext, String organisationId) {
        byte[] dataKey = new byte[DATA_KEY_LENGTH_BYTES];
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(dataKey);
        random.nextBytes(iv);

        try {
            Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            aes.updateAAD(aad(organisationId));
            byte[] ciphertext = aes.doFinal(plaintext.getBytes(UTF_8));

            Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
            rsa.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameters());
            byte[] wrappedKey = rsa.doFinal(dataKey);

            ByteBuffer envelope = ByteBuffer.allocate(2 + wrappedKey.length + iv.length + ciphertext.length);
            envelope.putShort((short) wrappedKey.length);
            envelope.put(wrappedKey);
            envelope.put(iv);
            envelope.put(ciphertext);

            return Base64.getEncoder().encodeToString(envelope.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret value", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

}
