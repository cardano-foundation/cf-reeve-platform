package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.RestAssured;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import org.cardanofoundation.lob.app.accounting_reporting_core.config.JaversConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.JpaConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.TimeConfig;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

@SpringBootTest(classes = {JaversConfig.class, TimeConfig.class, JpaConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@EnableAutoConfiguration(exclude = {
        SecurityAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class
})
@ComponentScan(basePackages = {"org.cardanofoundation.lob"})
@ContextConfiguration(classes = TestContainerConfig.class)
@EnableJpaRepositories
@EntityScan
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@Testcontainers
@ActiveProfiles("test")
abstract class WebBaseIntegrationTest {

    @LocalServerPort
    protected int serverPort;
    protected static WireMockServer wireMockServer;
    protected int randomWebMockPort = 49000;
    @Autowired
    private PostgreSQLContainer postgresContainer;
    @MockitoBean
    private KeycloakSecurityHelper keycloakSecurityHelper;

    @BeforeEach
    void allowOrganisationAccess() {
        when(keycloakSecurityHelper.canUserAccessOrg(anyString())).thenReturn(true);
    }

    @BeforeAll
    void setUp() {
        log.info("WireMockServer port: {}", randomWebMockPort);
        log.info("Local server port: {}", serverPort);

        wireMockServer = new WireMockServer(randomWebMockPort);
        wireMockServer.start();

        RestAssured.port = serverPort;
        RestAssured.baseURI = "http://localhost";

        Flyway flyway = Flyway.configure()
                .dataSource(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(), postgresContainer.getPassword())
                .locations("classpath:db/migration/postgresql/dev", "classpath:db/migration/postgresql/common")
                .load();
        flyway.migrate();
    }

    @AfterAll
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

}
