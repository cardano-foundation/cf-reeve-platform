package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lob.funding.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.funding")
public class FundingModuleConfig {
}
