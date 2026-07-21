package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import org.cardanofoundation.lob.app.keri_attestation.config.KeriAttestationProperties;

@Configuration
@ConditionalOnProperty(name = "lob.keri-attestation.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.keri_attestation")
@EnableConfigurationProperties(KeriAttestationProperties.class)
@EnableAsync
public class KeriAttestationModuleConfig {
}
