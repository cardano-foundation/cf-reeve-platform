package org.cardanofoundation.lob.app.support.crypto;

/**
 * Reversible encryption for secret configuration values that must travel through
 * domain events and be stored at rest. Implementations must be thread-safe.
 */
public interface SecretCipher {

    /**
     * @return a versioned envelope, never the raw ciphertext
     */
    String encrypt(String plaintext);

    /**
     * @throws IllegalArgumentException if the envelope version is unknown
     * @throws IllegalStateException    if authentication fails (tampered or wrong key)
     */
    String decrypt(String envelope);

}
