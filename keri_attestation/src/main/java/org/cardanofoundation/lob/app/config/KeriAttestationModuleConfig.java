package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

/**
 * The wallet-interaction flow (credential presentation, ATTEST, AUTH_BEGIN) runs synchronously in the
 * request thread (design rev, user-directed — cip113 parity; see {@code KeriCredentialService},
 * {@code KeriAttestService}, {@code KeriAuthBeginService}), so this module no longer needs {@code
 * @EnableAsync} or any dedicated background executor.
 */
@Configuration
@ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.keri_attestation")
@EnableConfigurationProperties(KeriAttestationProperties.class)
public class KeriAttestationModuleConfig {
}
