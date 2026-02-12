package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lob.accounting_reporting_core.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "org.cardanofoundation.lob.app.accounting_reporting_core")
public class AccountingCoreModuleConfig {
}
