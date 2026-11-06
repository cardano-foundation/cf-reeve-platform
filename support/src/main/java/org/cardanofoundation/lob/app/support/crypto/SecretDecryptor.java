package org.cardanofoundation.lob.app.support.crypto;

/**
 * Decrypt-only half of the configuration-secret cipher.
 * <p>
 * Only the netsuite tier holds this capability (private key). The {@code organisationId} must be
 * the same value the envelope was encrypted for — it is bound as authenticated data, so a
 * mismatch fails the integrity check exactly like tampering.
 */
public interface SecretDecryptor {

    /**
     * @param envelope       an envelope produced by {@link SecretEncryptor}
     * @param organisationId the immutable organisation primary key the envelope was encrypted for
     * @return the plaintext secret
     * @throws IllegalStateException    when the envelope cannot be decrypted
     * @throws IllegalArgumentException when the envelope is null or blank
     */
    String decrypt(String envelope, String organisationId);

}
