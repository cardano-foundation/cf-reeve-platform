package org.cardanofoundation.lob.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "lob.blockchain_publisher.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = {"org.cardanofoundation.lob.app.blockchain_publisher", "org.cardanofoundation.lob.app.blockchain_common"})
public class BlockchainPublisherModuleConfig {
}
