package org.cardanofoundation.lob.app.accounting_reporting_core.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.IndexerTransactionTransformer;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.OnChainIndexerReconcilationService;
import org.cardanofoundation.lob.app.accounting_reporting_core.service.internal.OnChainIndexerService;

@Configuration
@ConditionalOnProperty(value = "lob.indexer.enabled", havingValue = "true", matchIfMissing = false)
public class OnChainIndexerConfig {

    @Value("${lob.indexer.url}")
    private String indexerBaseUrl;

    @Value("${lob.indexer.page-size:100}")
    private int pageSize;

    @Value("${lob.indexer.connect-timeout:5000}")
    private int connectTimeoutMillis;

    @Value("${lob.indexer.read-timeout:30000}")
    private int readTimeoutMillis;

    @Bean
    @Qualifier("indexerRestClient")
    public RestClient indexerRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactory factory = indexerRequestFactory();
        return builder.requestFactory(factory).build();
    }

    @Bean
    public ClientHttpRequestFactory indexerRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMillis);
        factory.setReadTimeout(readTimeoutMillis);
        return factory;
    }

    @Bean
    public OnChainIndexerService onChainIndexerService(
            ObjectMapper objectMapper,
            @Qualifier("indexerRestClient") RestClient restClient) {
        return new OnChainIndexerService(objectMapper, restClient, indexerBaseUrl, pageSize);
    }

    @Bean
    public OnChainIndexerReconcilationService onChainIndexerReconcilationService(
            OnChainIndexerService onChainIndexerService,
            IndexerTransactionTransformer indexerTransactionTransformer) {
        return new OnChainIndexerReconcilationService(onChainIndexerService, indexerTransactionTransformer);
    }
}
