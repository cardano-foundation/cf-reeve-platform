package org.cardanofoundation.lob.app.blockchain_publisher.service.event_handle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainerConfig {

    public static final String POSTGRES_IMAGE = "postgres:16.3";

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "testcontainers.enabled", havingValue = "true", matchIfMissing = true)
    public PostgreSQLContainer<?> postgreSQLContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
    }
}
