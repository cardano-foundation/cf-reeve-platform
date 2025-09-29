package org.cardanofoundation.lob.app.blockchain_reader.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import org.cardanofoundation.lob.app.blockchain_common.domain.CardanoNetwork;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApi;
import org.cardanofoundation.lob.app.blockchain_reader.BlockchainReaderPublicApiIF;

@Configuration
@Slf4j
public class BlockchainReaderConfig {

    @Value("${lob.blockchain_reader.lob_follower_base_url:http://localhost:9090/api}")
    private String lobFollowerUrl;

    @Bean
    @ConditionalOnProperty(prefix = "lob.blockchain_reader", value = "enabled", havingValue = "true", matchIfMissing = true)
    public BlockchainReaderPublicApiIF blockchainReaderPublicApiReal(@Qualifier("blockchainReaderRestClient") RestClient restClient, CardanoNetwork network) {
        log.info("Creating BlockchainReaderPublicApi with real YACI service, blockchain_reader enabled.");

        return new BlockchainReaderPublicApi(restClient, network, lobFollowerUrl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lob.blockchain_reader", value = "enabled", havingValue = "false")
    public BlockchainReaderPublicApiIF blockchainReaderPublicApiMock(CardanoNetwork network) {
        log.info("Creating BlockchainReaderPublicApi with fake YACI service, blockchain_reader disabled.");

        return new BlockchainReaderPublicApiIF.Noop(network);
    }

}
