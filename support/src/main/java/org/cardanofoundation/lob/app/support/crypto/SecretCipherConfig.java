package org.cardanofoundation.lob.app.support.crypto;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers only the {@link SecretCipherPairCheck} startup canary.
 * <p>
 * This configuration deliberately provides NO key beans: the encryptor belongs to the
 * organisation module ({@code OrganisationCryptoConfig}, gated on {@code lob.organisation.enabled})
 * and the decryptor to the netsuite module ({@code NetsuiteCryptoConfig}, gated on
 * {@code lob.netsuite.enabled}). Since nothing injects a bean from here, this class stays
 * registered only because BOTH of those configs {@code @Import} it — Spring dedupes the double
 * import, and either one alone keeps the check registered in the merged shape. Dropping both is
 * caught by the merged-shape context test in the netsuite module, where a mismatched key pair
 * must fail startup. (A single surviving import is sufficient by construction: the check only
 * ever acts in contexts where both crypto configs are active.)
 */
@Configuration
public class SecretCipherConfig {

    @Bean
    public SecretCipherPairCheck secretCipherPairCheck(ObjectProvider<SecretEncryptor> encryptorProvider,
                                                       ObjectProvider<SecretDecryptor> decryptorProvider) {
        return new SecretCipherPairCheck(encryptorProvider, decryptorProvider);
    }

}
