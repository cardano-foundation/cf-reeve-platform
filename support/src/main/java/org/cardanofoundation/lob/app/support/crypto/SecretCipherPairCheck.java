package org.cardanofoundation.lob.app.support.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Startup canary for merged deployments (both the organisation and the netsuite module enabled):
 * when an encryptor AND a decryptor exist in the same context, their keys must be a pair, or
 * every stored configuration would be written undecryptably. Single-tier contexts (api-only,
 * publisher-only) resolve only one provider and this check is a no-op.
 * <p>
 * Runs after all singletons exist, via {@link ObjectProvider}, so it has no bean-ordering or
 * conditional-on-bean hazards.
 * <p>
 * A failure is reported with the cipher's own message rather than a blanket "not a pair": the
 * decryptor already distinguishes a key-pair mismatch from other failures (provider problems,
 * malformed output), and relabelling everything as a mismatch would send operators to rotate
 * healthy keys.
 */
@RequiredArgsConstructor
@Slf4j
public class SecretCipherPairCheck implements SmartInitializingSingleton {

    private static final String CANARY_CONTEXT = "pair-check";
    private static final String CANARY_VALUE = "reeve-secret-cipher-pair-check";

    private final ObjectProvider<SecretEncryptor> encryptorProvider;
    private final ObjectProvider<SecretDecryptor> decryptorProvider;

    @Override
    public void afterSingletonsInstantiated() {
        SecretEncryptor encryptor = encryptorProvider.getIfAvailable();
        SecretDecryptor decryptor = decryptorProvider.getIfAvailable();

        if (encryptor == null || decryptor == null) {
            return;
        }

        String roundtripped;
        try {
            roundtripped = decryptor.decrypt(encryptor.encrypt(CANARY_VALUE, CANARY_CONTEXT), CANARY_CONTEXT);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Config encryption key pair check failed: " + e.getMessage(), e);
        }

        if (!CANARY_VALUE.equals(roundtripped)) {
            throw new IllegalStateException(
                    "Config encryption key pair check failed: the canary value did not survive the roundtrip");
        }

        log.info("Config encryption key pair verified");
    }

}
