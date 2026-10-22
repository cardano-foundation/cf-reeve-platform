package org.cardanofoundation.lob.app.support.crypto;

/**
 * Encrypt-only half of the configuration-secret cipher.
 * <p>
 * The organisation tier holds only this capability (public key): it can produce envelopes but can
 * never read one back. Decryption lives exclusively behind {@link SecretDecryptor} on the netsuite
 * tier. The {@code organisationId} is bound into the ciphertext as authenticated data, so an
 * envelope stored for one organisation cannot be replayed for another.
 */
public interface SecretEncryptor {

    /**
     * @param plaintext      the secret to protect (e.g. a NetSuite private-key PEM)
     * @param organisationId the immutable organisation primary key this secret belongs to
     * @return an opaque Base64 envelope, safe to store and to transit Kafka
     */
    String encrypt(String plaintext, String organisationId);

}
