package org.cardanofoundation.reeve.demoapplication.configs;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientNetsuiteConfig {

    @Bean("netsuiteRestClient")
    public RestClient restClient() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(Duration.ofSeconds(30)))
                        .setResponseTimeout(Timeout.of(Duration.ofSeconds(30)))
                        .build())
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setConnectionRequestTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeaders(headers -> {
                    headers.add("Accept", "application/json");
                    headers.add("Content-Type", "application/json");
                })
                .build();
    }
}
