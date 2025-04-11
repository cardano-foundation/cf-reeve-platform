package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.service.integration.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client.NetSuiteClient;

@Configuration
@Import({JsonConfig.class, RestConfig.class})
@Slf4j
public class NetsuiteClientConfig {

    @Bean
    public NetSuiteClient netSuiteClient(ObjectMapper objectMapper,
                                         RestClient restClient,
                                         @Value("${lob.netsuite.client.url}") String url,
                                         @Value("${lob.netsuite.client.token-url}") String tokenUrl,
                                         @Value("${lob.netsuite.client.private-key-file-path}") String privateKeyFilePath,
                                         @Value("${lob.netsuite.client.client-id}") String clientId,
                                         @Value("${lob.netsuite.client.certificate-id}") String certificateId
    ) {
        log.info("Creating NetSuite client with url: {}", url);
        return new NetSuiteClient(objectMapper, restClient, url, tokenUrl, privateKeyFilePath, certificateId, clientId);
    }

}
