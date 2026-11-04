package org.cardanofoundation.lob.app.organisation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.cardanofoundation.lob.app.support.crypto.ConfigEncryptionProperties;
import org.cardanofoundation.lob.app.support.crypto.RsaOaepHybridEncryptor;
import org.cardanofoundation.lob.app.support.crypto.SecretCipherConfig;
import org.cardanofoundation.lob.app.support.crypto.SecretEncryptor;

/**
 * Provides the encrypt-only {@link SecretEncryptor} (public key) for the organisation tier.
 * <p>
 * This deliberately lives <em>inside</em> {@code org.cardanofoundation.lob.app.organisation}:
 * the netsuite, accounting-core and csv module configs all component-scan this package, so this
 * class is registered on pods that do not run the organisation module at all. The
 * {@code lob.organisation.enabled} gate — the same condition as on
 * {@code NetSuiteConfigAdminService}, the only consumer — keeps it inert there: a publisher pod
 * configured with only the private key must boot.
 * <p>
 * The bean is created unconditionally when the module is enabled; fail-fast on a missing or
 * invalid key lives in the {@link RsaOaepHybridEncryptor} constructor, so the startup error names
 * {@code LOB_CONFIG_ENCRYPTION_PUBLIC_KEY} instead of Spring's generic missing-bean message.
 * <p>
 * The {@link SecretCipherConfig} import keeps the key-pair canary registered: that config provides
 * no bean anything injects, so only this import (and its twin on {@code NetsuiteCryptoConfig})
 * holds it in the context. Spring dedupes the double import.
 */
@Configuration
@ConditionalOnProperty(name = "lob.organisation.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ConfigEncryptionProperties.class)
@Import(SecretCipherConfig.class)
public class OrganisationCryptoConfig {

    @Bean
    @ConditionalOnMissingBean(SecretEncryptor.class)
    public SecretEncryptor secretEncryptor(ConfigEncryptionProperties properties) {
        return new RsaOaepHybridEncryptor(properties.publicKey());
    }

}
