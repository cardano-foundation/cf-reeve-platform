package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.cardanofoundation.lob.app.support.crypto.SecretCipherConfig;

@Configuration
@ConditionalOnProperty(name = "lob.netsuite.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = {"org.cardanofoundation.lob.app.organisation", "org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter"})
@Import(SecretCipherConfig.class)
public class NetsuiteModuleConfig {
}
