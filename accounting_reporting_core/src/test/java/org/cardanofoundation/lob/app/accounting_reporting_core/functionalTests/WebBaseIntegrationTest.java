package org.cardanofoundation.lob.app.accounting_reporting_core.functionalTests;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.RestAssured;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import org.cardanofoundation.lob.app.accounting_reporting_core.config.JaversConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.JpaConfig;
import org.cardanofoundation.lob.app.accounting_reporting_core.config.TimeConfig;

@SpringBootTest(classes = {JaversConfig.class, TimeConfig.class, JpaConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@EnableAutoConfiguration
@ComponentScan(basePackages = {"org.cardanofoundation.lob"})
@ContextConfiguration(classes = TestContainerConfig.class)
@EnableJpaRepositories
@EntityScan
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@Testcontainers
public class WebBaseIntegrationTest {

    @LocalServerPort
    protected int serverPort;
    protected static WireMockServer wireMockServer;
    protected int randomWebMockPort = 19000;

    @BeforeAll
    void setUp() {
        log.info("WireMockServer port: {}", randomWebMockPort);
        log.info("Local server port: {}", serverPort);

        wireMockServer = new WireMockServer(randomWebMockPort);
        wireMockServer.start();

        RestAssured.port = serverPort;
        RestAssured.baseURI = "http://localhost";
    }

    @AfterAll
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

}
