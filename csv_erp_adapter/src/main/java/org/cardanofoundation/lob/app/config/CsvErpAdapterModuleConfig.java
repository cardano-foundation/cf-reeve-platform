package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lob.csv.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = { "org.cardanofoundation.lob.app.organisation", "org.cardanofoundation.lob.app.csv_erp_adapter"})
public class CsvErpAdapterModuleConfig {
}
