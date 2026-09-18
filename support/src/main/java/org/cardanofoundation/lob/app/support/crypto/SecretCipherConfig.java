package org.cardanofoundation.lob.app.support.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by every module config that needs to encrypt or decrypt stored secrets.
 * <p>
 * Both the organisation tier (which encrypts) and the netsuite tier (which decrypts) need the
 * same key, so in a merged deployment more than one module config imports this. Spring dedupes
 * the imported configuration class; {@link ConditionalOnMissingBean} guards the remaining case
 * where an application declares its own {@link SecretCipher}.
 */
@Configuration
public class SecretCipherConfig {

    @Bean
    @ConditionalOnMissingBean(SecretCipher.class)
    public SecretCipher secretCipher(@Value("${lob.security.config-encryption.key:}") String base64Key) {
        return new AesGcmSecretCipher(base64Key);
    }

}
