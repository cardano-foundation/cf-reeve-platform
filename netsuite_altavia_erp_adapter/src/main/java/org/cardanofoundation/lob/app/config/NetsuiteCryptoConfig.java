package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.cardanofoundation.lob.app.support.crypto.ConfigEncryptionProperties;
import org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridDecryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretCipherConfig;
import org.cardanofoundation.lob.app.support.crypto.SecretDecryptor;

/**
 * Provides the decrypt-only {@link SecretDecryptor} (private key) for the netsuite tier — the
 * counterpart of {@code OrganisationCryptoConfig}. Only pods with {@code lob.netsuite.enabled}
 * hold decryption capability; the api pod gets the public key alone and can never read stored
 * credentials back.
 * <p>
 * The bean is created unconditionally when the module is enabled; fail-fast on a missing or
 * invalid key lives in the {@link RsaOaepHybridDecryptor} constructor, so the startup error names
 * {@code LOB_CONFIG_ENCRYPTION_PRIVATE_KEY}.
 * <p>
 * The {@link SecretCipherConfig} import keeps the key-pair canary registered for merged
 * deployments (both modules enabled): see the note on {@code OrganisationCryptoConfig}.
 */
@Configuration
@ConditionalOnProperty(name = "lob.netsuite.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ConfigEncryptionProperties.class)
@Import(SecretCipherConfig.class)
public class NetsuiteCryptoConfig {

    @Bean
    @ConditionalOnMissingBean(SecretDecryptor.class)
    public SecretDecryptor secretDecryptor(ConfigEncryptionProperties properties) {
        return new RsaOaepHybridDecryptor(properties.privateKey());
    }

}
